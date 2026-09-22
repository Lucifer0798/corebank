# python:3.13-slim rather than alpine: scikit-learn and its scipy/numpy dependencies ship
# manylinux wheels that musl cannot use, so alpine would compile them from source and turn a
# one-minute build into a very long one. Same class of trap as the protoc/musl issue in the
# backend Dockerfile.
FROM python:3.13-slim

WORKDIR /app

# Dependencies before source, so an edit to app/ reuses the (slow) pip layer -- the reason the
# backend Dockerfile copies pom.xml before src/.
#
# Installed from the lock, not requirements.txt: the lock pins every package including transitive
# ones, so rebuilding this image a month from now installs what was tested rather than whatever
# the index serves that day. requirements.txt is the declared list and is copied only so the
# image carries a record of what was asked for alongside what was resolved.
COPY requirements.txt requirements.lock ./
RUN pip install --no-cache-dir -r requirements.lock

COPY app/ ./app/

# The trained model and MLflow run history live outside the image so they survive a rebuild and
# can be inspected from the host; compose mounts a volume here.
RUN mkdir -p /var/lib/insights && \
    useradd --create-home --shell /usr/sbin/nologin insights && \
    chown -R insights:insights /app /var/lib/insights
USER insights

EXPOSE 8000

HEALTHCHECK --interval=10s --timeout=5s --start-period=90s --retries=10 \
    CMD python -c "import urllib.request,sys; sys.exit(0 if urllib.request.urlopen('http://localhost:8000/health', timeout=4).status == 200 else 1)"

CMD ["uvicorn", "app.main:app", "--host", "0.0.0.0", "--port", "8000"]
