# 构建Docker镜像
Write-Host "构建Docker镜像中..."
docker build -t sms-server-demo:latest .

if ($LASTEXITCODE -ne 0) {
    Write-Host "Docker构建失败！" -ForegroundColor Red
    exit 1
}

Write-Host "Docker镜像构建成功！" -ForegroundColor Green

# 提示用户如何部署到Kubernetes
Write-Host "\n要部署到Kubernetes，请运行以下命令："
Write-Host "1. 从外部配置文件创建ConfigMap（如需）："
Write-Host "   kubectl create configmap sms-server-config --from-file=app.json=/path/to/your/app.json"
Write-Host "\n2. 应用部署配置："
Write-Host "   kubectl apply -f k8s-deployment.yaml"
Write-Host "\n3. 检查部署状态："
Write-Host "   kubectl get pods -l app=sms-server-demo"
Write-Host "   kubectl logs -f deployment/sms-server-demo"