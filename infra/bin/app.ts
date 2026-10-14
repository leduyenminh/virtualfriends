#!/usr/bin/env node
import * as cdk from 'aws-cdk-lib';
import { VchatStack } from '../lib/vchat-stack';

const app = new cdk.App();
new VchatStack(app, 'VirtualFriends', {
  env: {
    account: process.env.CDK_DEFAULT_ACCOUNT,
    region: app.node.tryGetContext('vchat:region') ?? process.env.CDK_DEFAULT_REGION,
  },
});
