# -*- coding: utf-8 -*-
"""统一的日志配置（Task F：安全剩余项 + 依赖扫描 + 日志）。

此前 GIS Python 服务完全依赖 uvicorn 默认日志，没有统一格式、也没有落盘滚动。
这里用标准库提供一个轻量、零依赖的统一日志格式：控制台 + 按天滚动的文件
（python-gis-service.log），便于排查与审计。服务入口 main.py 在导入时调用
configure_logging()，对测试环境无副作用（测试直接 import gis.router，不触发）。
"""
import logging
import os
from logging.handlers import TimedRotatingFileHandler

_LOG_FORMAT = "%(asctime)s %(levelname)-8s %(name)s %(message)s"
_DATE_FORMAT = "%Y-%m-%dT%H:%M:%S"


def configure_logging(level: int = logging.INFO) -> None:
    """配置根日志：统一格式 + 控制台 + 按天滚动文件。

    幂等：多次 import / 热重载不会重复添加 handler。文件不可写时退化为仅控制台，
    绝不阻断服务启动。
    """
    root = logging.getLogger()
    root.setLevel(level)

    if getattr(root, "_gis_logging_configured", False):
        return

    formatter = logging.Formatter(_LOG_FORMAT, datefmt=_DATE_FORMAT)

    console = logging.StreamHandler()
    console.setFormatter(formatter)
    root.addHandler(console)

    log_path = os.path.join(
        os.path.dirname(os.path.dirname(os.path.abspath(__file__))),
        "python-gis-service.log",
    )
    try:
        file_handler = TimedRotatingFileHandler(
            log_path, when="midnight", backupCount=7, encoding="utf-8"
        )
        file_handler.setFormatter(formatter)
        root.addHandler(file_handler)
    except OSError:
        # 文件不可写（如只读环境）时仅保留控制台，不影响服务。
        root.warning("无法写入日志文件 %s，已退化为仅控制台输出", log_path)

    root._gis_logging_configured = True  # type: ignore[attr-defined]
    root.info("GIS 服务统一日志已初始化（level=%s, file=%s）", logging.getLevelName(level), log_path)
