provider "aws" {
  region = var.region
}

resource "aws_s3_bucket" "assets" {
  bucket = "agraph-assets"
}

resource "aws_s3_bucket_policy" "assets" {
  bucket = aws_s3_bucket.assets.id
}

module "cdn" {
  source = "./modules/cdn"
  bucket = aws_s3_bucket.assets.id
}

variable "region" {
  type = string
}

output "bucket_name" {
  value = aws_s3_bucket.assets.bucket
}
