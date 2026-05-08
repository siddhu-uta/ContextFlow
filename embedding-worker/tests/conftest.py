import sys
import os

# Make the embedding-worker package root importable from the tests directory
sys.path.insert(0, os.path.dirname(os.path.dirname(__file__)))
