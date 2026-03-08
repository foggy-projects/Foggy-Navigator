FROM python:3.11-slim

WORKDIR /app

# 复制代码
COPY pyproject.toml .
COPY src/ src/

# 安装依赖
RUN pip install --no-cache-dir .

# 复制响应配置
COPY responses/ responses/

# 环境变量
ENV MOCK_LLM_PORT=8200
ENV MOCK_LLM_RESPONSES_DIR=/app/responses

EXPOSE 8200

CMD ["python", "-m", "mock_llm.main"]
