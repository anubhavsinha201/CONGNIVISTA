# 012 — Load the INT8 model on device

`wayfinder:task` · Status: **blocked**

## Question

Wire `app/assets/models/af_int8.tflite` into `af_classifier.dart` per
`contracts/model.md` §3. Two silent failure modes to guard against explicitly: hardcoding
the quantisation scale/zero-point instead of reading them from the tensor's own metadata
(breaks silently if the model is ever reconverted), and z-scoring with sample standard
deviation instead of population std (`/n`, matching `ml/prepare_cinc2017.py` exactly).

Blocked by: 002 (First compile of the Dart).
