# -*- coding: utf-8 -*-
"""Pytest bootstrap: make the repo root importable so `import main` resolves
regardless of the current working directory pytest is launched from.
"""
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
if str(ROOT) not in sys.path:
    sys.path.insert(0, str(ROOT))
