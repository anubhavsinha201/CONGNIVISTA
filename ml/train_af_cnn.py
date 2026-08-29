"""Train the 1D AF CNN and export a full-integer INT8 TFLite model.

Run:  python ml/train_af_cnn.py --seed 0
      python ml/train_af_cnn.py --seed 0 --epochs 40

Per seed this writes into ml/artifacts/:
    af_seed{N}.keras          FP32 model
    af_int8_seed{N}.tflite    full-integer quantised model
    scores_seed{N}.npz        FP32 and INT8 scores on the held-out test split

The scores file is what ml/calibrate_threshold.py consumes. Training and calibration are
separate steps on purpose: the calibration must be re-runnable without retraining, and
the threshold must be derived from measured scores rather than chosen alongside the model.

Input/output contract: contracts/model.md.
"""

from __future__ import annotations

import argparse
import json
import os

import numpy as np

HERE = os.path.dirname(os.path.abspath(__file__))
DATA = os.path.join(HERE, "data", "cinc2017_250hz.npz")
ARTIFACTS = os.path.join(HERE, "artifacts")

WINDOW = 7500


def build_model(seed: int):
    import tensorflow as tf
    from tensorflow.keras import layers

    tf.keras.utils.set_random_seed(seed)

    # Kept deliberately small. This has to run on a budget phone, and the
    # differentiator needs a model whose quantisation shift is measurable, not a
    # model that tops a leaderboard. Strided convs do the early downsampling so a
    # 7500-sample input reaches the classifier head cheaply.
    inp = layers.Input(shape=(WINDOW, 1), name="ecg")
    x = inp
    for filters, kernel, stride in [
        (16, 7, 2),
        (32, 5, 2),
        (64, 5, 2),
        (64, 3, 2),
        (128, 3, 2),
    ]:
        x = layers.Conv1D(filters, kernel, strides=stride, padding="same",
                          use_bias=False)(x)
        x = layers.BatchNormalization()(x)
        x = layers.ReLU()(x)
        x = layers.MaxPooling1D(2)(x)

    x = layers.GlobalAveragePooling1D()(x)
    x = layers.Dense(64)(x)
    x = layers.ReLU()(x)
    x = layers.Dropout(0.3)(x)
    out = layers.Dense(1, activation="sigmoid", name="af")(x)

    model = tf.keras.Model(inp, out, name=f"af_cnn_seed{seed}")
    model.compile(
        optimizer=tf.keras.optimizers.Adam(1e-3),
        loss="binary_crossentropy",
        metrics=[tf.keras.metrics.AUC(name="auc"),
                 tf.keras.metrics.AUC(name="prauc", curve="PR")],
    )
    return model


def to_int8_tflite(model, x_rep: np.ndarray, path: str) -> None:
    """Full-integer quantisation: int8 weights, activations, input AND output.

    Not dynamic-range. Full-integer is the honest deployment target for a budget
    phone, and it is the variant whose score shift the calibration measures.
    """
    import tensorflow as tf

    def representative():
        # Drawn from the TRAINING split only. Using validation or test data here
        # would leak held-out information into the deployed model's activation
        # ranges, which is subtle and would invalidate the calibration numbers.
        idx = np.random.default_rng(0).choice(len(x_rep), size=min(500, len(x_rep)),
                                              replace=False)
        for i in idx:
            yield [x_rep[i][None, :, :].astype(np.float32)]

    conv = tf.lite.TFLiteConverter.from_keras_model(model)
    conv.optimizations = [tf.lite.Optimize.DEFAULT]
    conv.representative_dataset = representative
    conv.target_spec.supported_ops = [tf.lite.OpsSet.TFLITE_BUILTINS_INT8]
    conv.inference_input_type = tf.int8
    conv.inference_output_type = tf.int8
    blob = conv.convert()
    with open(path, "wb") as fh:
        fh.write(blob)


def int8_scores(tflite_path: str, x: np.ndarray) -> np.ndarray:
    """Run the quantised model, applying the tensor's own scale/zero-point.

    Mirrors what the app must do (contracts/model.md section 3). Hardcoding these
    would break the moment the model is reconverted.
    """
    import tensorflow as tf

    interp = tf.lite.Interpreter(model_path=tflite_path)
    interp.allocate_tensors()
    inp = interp.get_input_details()[0]
    out = interp.get_output_details()[0]
    in_scale, in_zp = inp["quantization"]
    out_scale, out_zp = out["quantization"]

    scores = np.empty(len(x), dtype=np.float64)
    for i in range(len(x)):
        q = np.round(x[i] / in_scale) + in_zp
        q = np.clip(q, -128, 127).astype(np.int8)[None, :, :]
        interp.set_tensor(inp["index"], q)
        interp.invoke()
        raw = interp.get_tensor(out["index"])[0][0]
        scores[i] = (float(raw) - out_zp) * out_scale
    return scores


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--seed", type=int, default=0)
    ap.add_argument("--epochs", type=int, default=30)
    ap.add_argument("--batch", type=int, default=64)
    args = ap.parse_args()

    os.makedirs(ARTIFACTS, exist_ok=True)

    import tensorflow as tf

    gpus = tf.config.list_physical_devices("GPU")
    print("GPU:", gpus if gpus else "NONE (CPU)")

    d = np.load(DATA, allow_pickle=True)
    meta = json.loads(str(d["meta_json"]))
    print("dataset:", json.dumps({k: meta[k] for k in meta if "windows" in k or "records" in k}))

    X_tr = d["X_train"][..., None]
    y_tr = d["y_train"].astype(np.float32)
    X_va = d["X_val"][..., None]
    y_va = d["y_val"].astype(np.float32)
    X_te = d["X_test"][..., None]
    y_te = d["y_test"].astype(np.float32)

    pos, neg = float(y_tr.sum()), float(len(y_tr) - y_tr.sum())
    class_weight = {0: 1.0, 1: neg / max(pos, 1.0)}
    print(f"train windows={len(y_tr)}  AF={int(pos)}  class_weight[1]={class_weight[1]:.2f}")

    model = build_model(args.seed)
    model.fit(
        X_tr, y_tr,
        validation_data=(X_va, y_va),
        epochs=args.epochs,
        batch_size=args.batch,
        class_weight=class_weight,
        callbacks=[
            tf.keras.callbacks.EarlyStopping(monitor="val_prauc", mode="max",
                                             patience=6, restore_best_weights=True),
            tf.keras.callbacks.ReduceLROnPlateau(monitor="val_prauc", mode="max",
                                                 factor=0.5, patience=3, min_lr=1e-5),
        ],
        verbose=2,
    )

    keras_path = os.path.join(ARTIFACTS, f"af_seed{args.seed}.keras")
    model.save(keras_path)

    tflite_path = os.path.join(ARTIFACTS, f"af_int8_seed{args.seed}.tflite")
    print("converting to full-integer INT8 ...")
    to_int8_tflite(model, X_tr, tflite_path)
    print(f"  {tflite_path}  ({os.path.getsize(tflite_path) / 1024:.0f} KB)")

    print("scoring test split (FP32) ...")
    s_fp32 = model.predict(X_te, batch_size=args.batch, verbose=0).ravel().astype(np.float64)
    print("scoring test split (INT8) ...")
    s_int8 = int8_scores(tflite_path, X_te)

    np.savez_compressed(
        os.path.join(ARTIFACTS, f"scores_seed{args.seed}.npz"),
        y_true=y_te, fp32=s_fp32, int8=s_int8, rec=d["rec_test"], seed=args.seed,
    )

    from sklearn.metrics import roc_auc_score
    print(f"\nseed {args.seed}  test AUC  FP32={roc_auc_score(y_te, s_fp32):.4f}  "
          f"INT8={roc_auc_score(y_te, s_int8):.4f}")
    print("next: python ml/calibrate_threshold.py")


if __name__ == "__main__":
    main()
