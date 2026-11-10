"""Calibration images for int8 exports (QNN 8a8w/16a8w): LFW pairs test split, centre-cropped faces."""
import io
import os
import urllib.request

import numpy as np
import pyarrow.parquet as pq
import torch
from PIL import Image

import facenet_model

PAIRS_URL = "https://huggingface.co/datasets/logasja/lfw/resolve/main/pairs/test-00000-of-00001.parquet"


def to_input(raw):
    img = Image.open(io.BytesIO(raw)).convert("RGB").crop((65, 65, 185, 185)).resize((160, 160), Image.BILINEAR)
    return torch.from_numpy(np.asarray(img, dtype=np.float32))[None]


def rows():
    path = os.path.join(facenet_model.CACHE, "lfw-pairs-test.parquet")
    if not os.path.exists(path):
        os.makedirs(facenet_model.CACHE, exist_ok=True)
        urllib.request.urlretrieve(PAIRS_URL, path)
    return pq.read_table(path).to_pylist()


def batches(n_pairs=64, seed=0):
    data = rows()
    for i in np.random.default_rng(seed).permutation(len(data))[:n_pairs]:
        yield (to_input(data[i]["img_0"]["bytes"]),)
        yield (to_input(data[i]["img_1"]["bytes"]),)
