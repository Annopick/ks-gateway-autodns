FROM python:3.11-alpine

WORKDIR /app

# Install build dependencies for netifaces
RUN apk add --no-cache gcc musl-dev linux-headers

# Install dependencies
COPY requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt

# Copy application
COPY agent.py .

# Create non-root user
RUN adduser -D -u 1001 appuser && \
    chown -R appuser:appuser /app

USER appuser

# Run the agent
CMD ["python", "-u", "agent.py"]
