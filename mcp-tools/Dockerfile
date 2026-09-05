FROM python:3.12-slim

WORKDIR /app

COPY pyproject.toml ./
RUN pip install --no-cache-dir --timeout 300 --retries 10 fastapi==0.115.0 "uvicorn[standard]==0.31.1" httpx==0.27.2 pydantic==2.11.7 redis==5.0.8 mcp==1.28.0 sse-starlette==2.1.3

COPY app ./app

EXPOSE 7001

CMD ["uvicorn", "app.main:app", "--host", "0.0.0.0", "--port", "7001"]
