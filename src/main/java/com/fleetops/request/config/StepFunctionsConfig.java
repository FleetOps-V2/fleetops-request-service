package com.fleetops.request.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.sfn.SfnClient;

@Configuration
public class StepFunctionsConfig {

    @Value("${app.step-functions.state-machine-arn:}")
    private String stateMachineArn;

    @Value("${app.aws.region:us-east-1}")
    private String awsRegion;

    @Bean(destroyMethod = "close")
    public SfnClient sfnClient() {
        return SfnClient.builder()
                .region(Region.of(awsRegion))
                .build();
    }

    public String getStateMachineArn() {
        return stateMachineArn;
    }
}
