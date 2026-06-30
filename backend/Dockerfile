FROM python:3.12-slim

WORKDIR /app
COPY router_kill_switch_backend.py /app/router_kill_switch_backend.py

RUN mkdir -p /data
VOLUME ["/data"]

ENV RKS_BIND=0.0.0.0
ENV RKS_PORT=8765

EXPOSE 8765
CMD ["python", "/app/router_kill_switch_backend.py"]
