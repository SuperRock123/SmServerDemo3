#!/bin/bash

# 构建Docker镜像
echo "Building Docker image..."
docker build -t sms-server-demo:latest .

if [ $? -ne 0 ]; then
    echo "Docker build failed!"
    exit 1
fi

echo "Docker image built successfully!"

# 提示用户如何部署到Kubernetes
echo "\nTo deploy to Kubernetes, run the following commands:"
echo "1. Create ConfigMap from your external config file (if needed):"
echo "   kubectl create configmap sms-server-config --from-file=app.json=/path/to/your/app.json"
echo "\n2. Apply the deployment:"
echo "   kubectl apply -f k8s-deployment.yaml"
echo "\n3. To check the deployment status:"
echo "   kubectl get pods -l app=sms-server-demo"
echo "   kubectl logs -f deployment/sms-server-demo"