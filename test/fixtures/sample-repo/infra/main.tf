provider "aws" {
  alias = "edge"
  region = var.region
}

data "aws_caller_identity" "current" {
}

resource "aws_s3_bucket" "assets" {
  provider = aws.edge
  bucket = "ygg-assets"
  tags = {
    account = data.aws_caller_identity.current.account_id
  }
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
