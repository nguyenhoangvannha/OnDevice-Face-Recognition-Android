# FaceNet → ExecuTorch export

Reproducible export of the app's FaceNet model for each inference backend.
Context and decisions: [docs/coprocessor/inference-backends.md](../../docs/coprocessor/inference-backends.md).

## Source model (verified)

- `facenet-pytorch` `InceptionResnetV1`, VGGFace2 weights
  `20180402-114759-vggface2.pt` (sha256 `281cebca…25d99b`, pinned in `facenet_model.py`), classifier head dropped.
- Wrapper (`FaceNetNHWC`): input `[1,160,160,3]` RGB 0..255 (what `FaceNet.kt` feeds) → NCHW →
  `(x - 127.5) / 128` → 512-D L2-normalised embedding.
- Checked against the upstream `model.pte` (sha256 `06927e2f…0251e2`) on 5 face photos from the
  facenet-pytorch repo + 3 random images: cosine **1.000000**, max |diff| < 3e-7.

## Setup

```bash
cd tools/export
./setup.sh                                  # uv venv, Python 3.12, pinned requirements.txt
export PATH="$PWD/.venv/bin:$PATH"          # export needs .venv/bin/flatc on PATH
```

`.venv/`, `.cache/` (weights, test images, LFW parquet) and `out/` are git-ignored.

## Export

```bash
python export_pte.py --backend xnnpack      # out/facenet-xnnpack-fp32.pte (CPU)
python export_pte.py --backend vulkan       # out/facenet-vulkan-fp32.pte  (GPU)
python verify_pte.py out/facenet-xnnpack-fp32.pte --against ../../app/src/main/assets/model.pte
```

| Output | Size | sha256 | Notes |
| --- | --- | --- | --- |
| `facenet-xnnpack-fp32.pte` | 94,008,576 | `5dd4afc0…14b79e5` | Same numbers as upstream `model.pte` (the app keeps shipping upstream's file). |
| `facenet-vulkan-fp32.pte` | 94,010,112 | `6408b0d4…6d0b8bd` | 2 Vulkan subgraphs, 572 nodes delegated; 3 nodes (an `as_strided_copy` view after avg-pool) run on the CPU portable kernels. Published as release asset `models-v1`. |

Exports are deterministic: a fresh `./setup.sh` venv gives byte-identical files.

`verify_pte.py` runs on the host ExecuTorch runtime, which has XNNPACK (and Core ML) but **no Vulkan or
QNN**, so the Vulkan file cannot be run on the Mac; the app checks it on the phone instead (probe vs CPU).

## NPU (Qualcomm QNN, SM8350 / HTP V68) — not exported

`python export_pte.py --backend qnn --soc SM8350 --precision use_8a8w` is written but **not run**:

- It needs Linux x86_64 and the Qualcomm AI Runtime (QNN) SDK 2.37 (`QNN_SDK_ROOT`), plus the
  `executorch` Linux wheel (it contains `PyQnnManagerAdaptor`; the macOS wheel does not).
- The SDK is downloadable without an account, but it ships under Qualcomm's "AI Stack License", which
  you accept by downloading/using it and which lists biometric identification as a "High-Risk"
  use Qualcomm advises against. That decision is the owner's, so it was not accepted on his behalf.
- HTP V68 has no fp16, so the NPU model would be int8 (8a8w or 16a8w), calibrated with `calibration.py`.

`quant_drift.py` estimates the int8 effect on the host (XNNPACK quantizer as a proxy for QNN 8a8w,
600 LFW pairs): cosine(fp32, int8) mean 0.998, min 0.992; best verification accuracy 0.992 (fp32) vs
0.988 (int8) vs 0.990 (CPU-enrolled faces queried with int8). Real HTP numbers: not measured.
