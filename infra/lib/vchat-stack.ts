import * as path from 'path';
import * as fs from 'fs';
import * as cdk from 'aws-cdk-lib';
import { Construct } from 'constructs';
import * as ec2 from 'aws-cdk-lib/aws-ec2';
import * as ecs from 'aws-cdk-lib/aws-ecs';
import * as ecsPatterns from 'aws-cdk-lib/aws-ecs-patterns';
import * as rds from 'aws-cdk-lib/aws-rds';
import * as s3 from 'aws-cdk-lib/aws-s3';
import * as s3deploy from 'aws-cdk-lib/aws-s3-deployment';
import * as cloudfront from 'aws-cdk-lib/aws-cloudfront';
import * as origins from 'aws-cdk-lib/aws-cloudfront-origins';
import * as logs from 'aws-cdk-lib/aws-logs';
import * as secretsmanager from 'aws-cdk-lib/aws-secretsmanager';
import * as cloudwatch from 'aws-cdk-lib/aws-cloudwatch';
import * as elasticache from 'aws-cdk-lib/aws-elasticache';

/**
 * VirtualFriends on AWS:
 *   CloudFront (web) ─┬─ /*            → S3 (React build)
 *                     └─ /api/*, /ws*  → ALB → ECS Fargate (Spring Boot) → RDS PostgreSQL
 *   CloudFront (media)  → S3 (stickers, attachments, avatar models) on its own domain
 *   ElastiCache Redis    shared cache (TTS audio), presence, rate limits
 *   CloudWatch Logs (JSON app logs, 30 days) + alarms + dashboard
 */
export class VchatStack extends cdk.Stack {
  constructor(scope: Construct, id: string, props?: cdk.StackProps) {
    super(scope, id, props);

    const vpc = new ec2.Vpc(this, 'Vpc', { maxAzs: 2, natGateways: 1 });

    // ---------- database ----------
    const db = new rds.DatabaseInstance(this, 'Db', {
      engine: rds.DatabaseInstanceEngine.postgres({ version: rds.PostgresEngineVersion.VER_16 }),
      instanceType: ec2.InstanceType.of(ec2.InstanceClass.T4G, ec2.InstanceSize.MICRO),
      vpc,
      vpcSubnets: { subnetType: ec2.SubnetType.PRIVATE_WITH_EGRESS },
      databaseName: 'vchat',
      credentials: rds.Credentials.fromGeneratedSecret('vchat'),
      allocatedStorage: 20,
      maxAllocatedStorage: 100,
      backupRetention: cdk.Duration.days(7),
      deletionProtection: true,
      removalPolicy: cdk.RemovalPolicy.SNAPSHOT,
      cloudwatchLogsExports: ['postgresql'],
      // Query diagnostics: pg_stat_statements + slow-query log + auto_explain (plans of slow queries) into CloudWatch
      parameterGroup: new rds.ParameterGroup(this, 'DbParams', {
        engine: rds.DatabaseInstanceEngine.postgres({ version: rds.PostgresEngineVersion.VER_16 }),
        parameters: {
          shared_preload_libraries: 'pg_stat_statements,auto_explain',
          'pg_stat_statements.track': 'all',
          log_min_duration_statement: '500',
          'auto_explain.log_min_duration': '1000',
          track_io_timing: '1',
        },
      }),
    });

    // ---------- Redis (cache, presence, rate limits) ----------
    const redisSg = new ec2.SecurityGroup(this, 'RedisSg', { vpc, description: 'VirtualFriends Redis' });
    const redisSubnets = new elasticache.CfnSubnetGroup(this, 'RedisSubnets', {
      description: 'VirtualFriends Redis',
      subnetIds: vpc.privateSubnets.map((s) => s.subnetId),
    });
    const redis = new elasticache.CfnReplicationGroup(this, 'Redis', {
      replicationGroupDescription: 'VirtualFriends cache',
      engine: 'redis',
      cacheNodeType: 'cache.t4g.micro',
      numCacheClusters: 1,
      automaticFailoverEnabled: false,
      transitEncryptionEnabled: true,
      atRestEncryptionEnabled: true,
      cacheSubnetGroupName: redisSubnets.ref,
      securityGroupIds: [redisSg.securityGroupId],
    });

    // ---------- media ----------
    const media = new s3.Bucket(this, 'Media', {
      blockPublicAccess: s3.BlockPublicAccess.BLOCK_ALL,
      encryption: s3.BucketEncryption.S3_MANAGED,
      enforceSSL: true,
      removalPolicy: cdk.RemovalPolicy.RETAIN,
    });
    const mediaCdn = new cloudfront.Distribution(this, 'MediaCdn', {
      defaultBehavior: {
        origin: origins.S3BucketOrigin.withOriginAccessControl(media),
        viewerProtocolPolicy: cloudfront.ViewerProtocolPolicy.REDIRECT_TO_HTTPS,
        cachePolicy: cloudfront.CachePolicy.CACHING_OPTIMIZED,
        responseHeadersPolicy: new cloudfront.ResponseHeadersPolicy(this, 'MediaHeaders', {
          corsBehavior: {
            accessControlAllowOrigins: ['*'], accessControlAllowMethods: ['GET', 'HEAD'],
            accessControlAllowHeaders: ['*'], accessControlAllowCredentials: false, originOverride: true,
          },
          securityHeadersBehavior: { contentTypeOptions: { override: true } },
        }),
      },
    });

    // ---------- secrets (fill values after first deploy: aws secretsmanager put-secret-value ...) ----------
    const appSecrets = new secretsmanager.Secret(this, 'AppSecrets', {
      description: 'VirtualFriends API keys + OAuth client credentials + JWT_SECRET',
      generateSecretString: {
        secretStringTemplate: JSON.stringify({
          ANTHROPIC_API_KEY: '', ELEVENLABS_API_KEY: '', GOOGLE_TTS_API_KEY: '',
          GOOGLE_CLIENT_ID: '', GOOGLE_CLIENT_SECRET: '', FACEBOOK_CLIENT_ID: '', FACEBOOK_CLIENT_SECRET: '',
        }),
        generateStringKey: 'JWT_SECRET',
        passwordLength: 64,
        excludePunctuation: true,
      },
    });

    // ---------- backend ----------
    const cluster = new ecs.Cluster(this, 'Cluster', { vpc, containerInsights: true });
    const appLogs = new logs.LogGroup(this, 'AppLogs', {
      logGroupName: '/vchat/backend',
      retention: logs.RetentionDays.ONE_MONTH,
      removalPolicy: cdk.RemovalPolicy.DESTROY,
    });

    const service = new ecsPatterns.ApplicationLoadBalancedFargateService(this, 'Api', {
      cluster,
      cpu: 512,
      memoryLimitMiB: 1024,
      // One task: STOMP uses the in-memory broker. Scale out after moving to a broker relay (Amazon MQ).
      desiredCount: 1,
      publicLoadBalancer: true,
      circuitBreaker: { rollback: true },
      taskImageOptions: {
        image: ecs.ContainerImage.fromAsset(path.join(__dirname, '../../backend')),
        containerPort: 8080,
        logDriver: ecs.LogDrivers.awsLogs({ logGroup: appLogs, streamPrefix: 'api' }),
        environment: {
          SPRING_PROFILES_ACTIVE: 'aws',
          DB_URL: `jdbc:postgresql://${db.dbInstanceEndpointAddress}:${db.dbInstanceEndpointPort}/vchat`,
          STORAGE_MODE: 'S3',
          MEDIA_BUCKET: media.bucketName,
          MEDIA_BASE_URL: `https://${mediaCdn.distributionDomainName}`,
          AWS_REGION: this.region,
          REDIS_HOST: redis.attrPrimaryEndPointAddress,
          REDIS_PORT: redis.attrPrimaryEndPointPort,
          REDIS_SSL: 'true',
          // Viewers use HTTPS at CloudFront, but CloudFront -> ALB is http, so "auto" would drop the Secure flag
          COOKIE_SECURE: 'true',
          // Bootstrap admins at deploy time: ADMIN_USERNAMES=alice,bob npx cdk deploy
          ADMIN_USERNAMES: process.env.ADMIN_USERNAMES ?? '',
        },
        secrets: {
          DB_USER: ecs.Secret.fromSecretsManager(db.secret!, 'username'),
          DB_PASSWORD: ecs.Secret.fromSecretsManager(db.secret!, 'password'),
          ANTHROPIC_API_KEY: ecs.Secret.fromSecretsManager(appSecrets, 'ANTHROPIC_API_KEY'),
          ELEVENLABS_API_KEY: ecs.Secret.fromSecretsManager(appSecrets, 'ELEVENLABS_API_KEY'),
          GOOGLE_TTS_API_KEY: ecs.Secret.fromSecretsManager(appSecrets, 'GOOGLE_TTS_API_KEY'),
          JWT_SECRET: ecs.Secret.fromSecretsManager(appSecrets, 'JWT_SECRET'),
          GOOGLE_CLIENT_ID: ecs.Secret.fromSecretsManager(appSecrets, 'GOOGLE_CLIENT_ID'),
          GOOGLE_CLIENT_SECRET: ecs.Secret.fromSecretsManager(appSecrets, 'GOOGLE_CLIENT_SECRET'),
          FACEBOOK_CLIENT_ID: ecs.Secret.fromSecretsManager(appSecrets, 'FACEBOOK_CLIENT_ID'),
          FACEBOOK_CLIENT_SECRET: ecs.Secret.fromSecretsManager(appSecrets, 'FACEBOOK_CLIENT_SECRET'),
        },
      },
    });
    service.targetGroup.configureHealthCheck({ path: '/actuator/health', healthyHttpCodes: '200' });
    // Sticky sessions keep a WebSocket client on the same task if you raise desiredCount with a broker relay.
    service.targetGroup.enableCookieStickiness(cdk.Duration.hours(1));
    service.loadBalancer.setAttribute('idle_timeout.timeout_seconds', '300'); // long-lived WebSockets
    db.connections.allowDefaultPortFrom(service.service);
    redisSg.addIngressRule(service.service.connections.securityGroups[0], ec2.Port.tcp(6379), 'backend to Redis');
    media.grantReadWrite(service.taskDefinition.taskRole);

    // ---------- web (SPA + API on one origin, so no CORS and the WebSocket shares the domain) ----------
    const web = new s3.Bucket(this, 'Web', {
      blockPublicAccess: s3.BlockPublicAccess.BLOCK_ALL,
      enforceSSL: true,
      removalPolicy: cdk.RemovalPolicy.DESTROY,
      autoDeleteObjects: true,
    });
    const apiOrigin = new origins.LoadBalancerV2Origin(service.loadBalancer, {
      protocolPolicy: cloudfront.OriginProtocolPolicy.HTTP_ONLY,
      readTimeout: cdk.Duration.seconds(60),
    });
    const apiBehavior: cloudfront.BehaviorOptions = {
      origin: apiOrigin,
      viewerProtocolPolicy: cloudfront.ViewerProtocolPolicy.HTTPS_ONLY,
      allowedMethods: cloudfront.AllowedMethods.ALLOW_ALL,
      cachePolicy: cloudfront.CachePolicy.CACHING_DISABLED,
      originRequestPolicy: cloudfront.OriginRequestPolicy.ALL_VIEWER,
    };
    const webCdn = new cloudfront.Distribution(this, 'WebCdn', {
      defaultRootObject: 'index.html',
      defaultBehavior: {
        origin: origins.S3BucketOrigin.withOriginAccessControl(web),
        viewerProtocolPolicy: cloudfront.ViewerProtocolPolicy.REDIRECT_TO_HTTPS,
        // The SPA also ships a Content-Security-Policy <meta> (vite.config.js); these cover what a meta tag can't
        responseHeadersPolicy: new cloudfront.ResponseHeadersPolicy(this, 'WebHeaders', {
          securityHeadersBehavior: {
            strictTransportSecurity: { accessControlMaxAge: cdk.Duration.days(365), includeSubdomains: true, override: true },
            frameOptions: { frameOption: cloudfront.HeadersFrameOption.DENY, override: true },
            contentTypeOptions: { override: true },
            referrerPolicy: { referrerPolicy: cloudfront.HeadersReferrerPolicy.SAME_ORIGIN, override: true },
          },
        }),
      },
      additionalBehaviors: {
        '/api/*': apiBehavior, '/ws': apiBehavior, '/ws/*': apiBehavior,
        // Google/Facebook sign-in redirect + callback
        '/oauth2/*': apiBehavior, '/login/oauth2/*': apiBehavior,
      },
      errorResponses: [{ httpStatus: 403, responseHttpStatus: 200, responsePagePath: '/index.html' }],
    });
    const appUrl = `https://${webCdn.distributionDomainName}`;
    const api = service.taskDefinition.defaultContainer!;
    api.addEnvironment('CORS_ORIGINS', appUrl);
    api.addEnvironment('FRONTEND_URL', appUrl);
    api.addEnvironment('APP_BASE_URL', appUrl); // OAuth redirect URI base: {appUrl}/login/oauth2/code/{google|facebook}

    const dist = path.join(__dirname, '../../frontend/dist');
    if (fs.existsSync(dist)) {
      new s3deploy.BucketDeployment(this, 'DeployWeb', {
        sources: [s3deploy.Source.asset(dist)],
        destinationBucket: web,
        distribution: webCdn,
        distributionPaths: ['/index.html'],
      });
    }

    // ---------- observability ----------
    const errors5xx = service.loadBalancer.metrics.httpCodeTarget(
      cdk.aws_elasticloadbalancingv2.HttpCodeTarget.TARGET_5XX_COUNT, { period: cdk.Duration.minutes(5) });
    new cloudwatch.Alarm(this, 'Api5xx', { metric: errors5xx, threshold: 10, evaluationPeriods: 1,
      alarmDescription: 'Backend returning 5xx', treatMissingData: cloudwatch.TreatMissingData.NOT_BREACHING });
    new cloudwatch.Alarm(this, 'ApiCpu', { metric: service.service.metricCpuUtilization(), threshold: 80, evaluationPeriods: 3 });
    const botErrors = new logs.MetricFilter(this, 'BotErrors', {
      logGroup: appLogs,
      metricNamespace: 'VirtualFriends',
      metricName: 'ClaudeCallFailures',
      filterPattern: logs.FilterPattern.stringValue('$.message', '=', 'Claude call failed*'),
    });
    new cloudwatch.Dashboard(this, 'Dashboard', {
      dashboardName: 'VirtualFriends',
      widgets: [[
        new cloudwatch.GraphWidget({ title: 'Requests', left: [service.loadBalancer.metrics.requestCount()] }),
        new cloudwatch.GraphWidget({ title: '5xx', left: [errors5xx] }),
        new cloudwatch.GraphWidget({ title: 'CPU / Memory', left: [service.service.metricCpuUtilization(), service.service.metricMemoryUtilization()] }),
        new cloudwatch.GraphWidget({ title: 'Claude failures', left: [botErrors.metric()] }),
      ], [
        new cloudwatch.LogQueryWidget({ title: 'Recent errors', logGroupNames: [appLogs.logGroupName], width: 24,
          queryLines: ['fields @timestamp, level, logger_name, message', 'filter level = "ERROR" or level = "WARN"', 'sort @timestamp desc', 'limit 50'] }),
      ]],
    });

    new cdk.CfnOutput(this, 'AppUrl', { value: `https://${webCdn.distributionDomainName}` });
    new cdk.CfnOutput(this, 'MediaUrl', { value: `https://${mediaCdn.distributionDomainName}` });
    new cdk.CfnOutput(this, 'SecretsArn', { value: appSecrets.secretArn });
  }
}
