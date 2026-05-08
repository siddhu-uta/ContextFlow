#!/bin/bash
echo "Initializing LocalStack S3..."
awslocal s3 mb s3://contextflow-documents
awslocal s3api put-bucket-versioning \
  --bucket contextflow-documents \
  --versioning-configuration Status=Enabled
echo "S3 bucket 'contextflow-documents' created."
