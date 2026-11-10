"""FaceNet (facenet-pytorch InceptionResnetV1, VGGFace2 weights) wrapped with the app's I/O contract.

Input : float32 [1, 160, 160, 3], RGB, raw pixel values 0..255 (NHWC, what FaceNet.kt feeds).
Output: float32 [1, 512], L2-normalised embedding.
The wrapper does NHWC->NCHW and facenet-pytorch's fixed_image_standardization ((x - 127.5) / 128).
Verified against the upstream model.pte (sha256 06927e2f...0251e2): cosine 1.000000, max |diff| < 3e-7.
"""
import hashlib
import os
import urllib.request

import torch
from facenet_pytorch.models.inception_resnet_v1 import InceptionResnetV1

WEIGHTS_URL = "https://github.com/timesler/facenet-pytorch/releases/download/v2.2.9/20180402-114759-vggface2.pt"
WEIGHTS_SHA256 = "281cebca8662831adb987a874bdcb36e73f5b1c6dc5ee5878f305e985625d99b"
HERE = os.path.dirname(os.path.abspath(__file__))
CACHE = os.path.join(HERE, ".cache")


def sha256(path):
    h = hashlib.sha256()
    with open(path, "rb") as f:
        for chunk in iter(lambda: f.read(1 << 20), b""):
            h.update(chunk)
    return h.hexdigest()


def weights_path():
    os.makedirs(CACHE, exist_ok=True)
    path = os.path.join(CACHE, os.path.basename(WEIGHTS_URL))
    if not os.path.exists(path):
        urllib.request.urlretrieve(WEIGHTS_URL, path + ".part")
        os.replace(path + ".part", path)
    got = sha256(path)
    if got != WEIGHTS_SHA256:
        raise SystemExit(f"weights sha256 mismatch: {got}")
    return path


class FaceNetNHWC(torch.nn.Module):
    def __init__(self):
        super().__init__()
        self.net = InceptionResnetV1(pretrained=None, classify=False)
        state = torch.load(weights_path(), map_location="cpu")
        state = {k: v for k, v in state.items() if not k.startswith("logits.")}
        self.net.load_state_dict(state)
        self.net.eval()

    def forward(self, x):
        x = x.permute(0, 3, 1, 2)
        x = (x - 127.5) / 128.0
        return self.net(x)


def build():
    return FaceNetNHWC().eval()


def example_input():
    return (torch.zeros(1, 160, 160, 3),)
