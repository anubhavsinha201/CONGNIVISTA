#!/usr/bin/env bash
# Source this before running anything in ml/ on the WSL2 RTX 5070.
#
#   source ml/wsl_env.sh
#   python ml/train_af_cnn.py --seed 0
#
# WHY THIS EXISTS
# ---------------
# Ubuntu ships ptxas 12.4 at /usr/bin/ptxas. The RTX 5070 is Blackwell (sm_120),
# which ptxas only understands from CUDA 12.8 onward. TensorFlow finds the system
# one first on PATH, so XLA's Triton GEMM autotuner fails to compile any config and
# training dies with:
#
#   Autotuner could not compile any configs for HLO: %gemm_fusion_MatMul...
#   ptxas does not support CC 12.0
#
# Inference still works, because standard kernels get JIT-compiled by the driver.
# Only the backward pass hits the Triton path. So a forward-only GPU check passes
# and training then fails -- verify with a gradient step, not a matmul.
#
# tensorflow[and-cuda] already installs ptxas 12.9 inside the venv. This just puts
# it ahead of the system one.

VENV="$HOME/arogyax-ml/.venv"
NVCC_DIR="$VENV/lib/python3.13/site-packages/nvidia/cuda_nvcc"

if [ ! -d "$NVCC_DIR" ]; then
  echo "WARNING: $NVCC_DIR not found -- is the venv installed?" >&2
else
  export PATH="$NVCC_DIR/bin:$PATH"
  export XLA_FLAGS="--xla_gpu_cuda_data_dir=$NVCC_DIR"
fi

# shellcheck disable=SC1091
[ -f "$VENV/bin/activate" ] && source "$VENV/bin/activate"

export TF_CPP_MIN_LOG_LEVEL=1

echo "python: $(python --version 2>&1)"
echo "ptxas:  $(ptxas --version 2>/dev/null | tail -1)"
