import yaml
import os

_config = None

def load_config():
    global _config
    config_path = os.environ.get("THEFRAME_CONFIG", os.path.join(os.path.dirname(__file__), "config.yml"))
    with open(config_path) as f:
        _config = yaml.safe_load(f)
    return _config

def get_config():
    if _config is None:
        load_config()
    return _config

def tv_config():
    return get_config()["tv"]

def input_map():
    return get_config().get("inputs", {})

def server_config():
    return get_config().get("server", {"host": "0.0.0.0", "port": 8088})
