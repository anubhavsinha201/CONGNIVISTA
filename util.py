import os
import numpy as np
import torch
from torch import nn
import random
from scipy import signal

def set_seed(seed=42):
    '''reproducibility purposes I'll be setting all the seeds to be the same value'''
    random.seed(seed)
    np.random.seed(seed)
    torch.random.seed(seed)
    