import * as pulumi from "@pulumi/pulumi";
import * as aws from "@pulumi/aws";

const stack = pulumi.getStack()
const config = new pulumi.Config(stack)

const bucketName = "elasticsearch-remote-synonym";
const synonymsPluginBucket = aws.s3.getBucketOutput({
    bucket: bucketName
})

const natIps = config.requireObject<string[]>("natIps")

new aws.s3.BucketPolicy(bucketName, {
    bucket: synonymsPluginBucket.bucket,
    policy: synonymsPluginBucket.arn
                .apply( b => JSON.stringify({
                    "Version": "2012-10-17",
                    "Id": "bucketpolicy",
                    "Statement": [
                        { 
                            "Sid": "AllowNatGwAccess",
                            "Effect": "Allow",
                            "Principal": "*",
                            "Action": "s3:GetObject",
                            "Resource": `${b}/*`,
                            "Condition": {
                                "IpAddress": {
                                    "aws:SourceIp": natIps
                                }   
                            }
                        }
                    ]
                }
        )
    )
})
