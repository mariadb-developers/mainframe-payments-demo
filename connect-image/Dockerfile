# Custom Kafka Connect image for the mainframe-payments demo (cdc_connectors Path A).
# Extends the stock Debezium Connect image with the demo's two custom connector
# plugins baked into the plugin path (/kafka/connect), so Connect loads them on
# startup — no plugins[] URL fetch needed.
#
# Build context is the demo repo root (the fat-jars live under each module's
# build/libs, which is gitignored — build them first):
#   ./gradlew :cdc-sink:shadowJar :gg-cache-publisher:shadowJar
#   docker build -f connect-image/Dockerfile -t ghcr.io/escapedcanadian/mainframe-payments-connect:0.0.1 .
#   docker push ghcr.io/escapedcanadian/mainframe-payments-connect:0.0.1
# The pushed GHCR package must be PUBLIC (the cdc_connectors Connect Deployment
# sets no imagePullSecret for kafka_connect.image).
FROM debezium/connect:3.0.0.Final

COPY cdc-sink/build/libs/cdc-sink-0.0.1-SNAPSHOT.jar \
     /kafka/connect/cdc-sink/cdc-sink.jar
COPY gg-cache-publisher/build/libs/gg-cache-publisher-0.0.1-SNAPSHOT.jar \
     /kafka/connect/gg-cache-publisher/gg-cache-publisher.jar
