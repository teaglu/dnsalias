package com.teaglu.dnsalias.dns.route53;

import java.net.URI;
import java.net.URISyntaxException;

import org.eclipse.jdt.annotation.NonNull;

import com.teaglu.composite.Composite;
import com.teaglu.composite.exception.SchemaException;

import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.awssdk.auth.credentials.AwsSessionCredentials;
import software.amazon.awssdk.auth.credentials.InstanceProfileCredentialsProvider;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.sts.StsClient;
import software.amazon.awssdk.services.route53.Route53Client;
import software.amazon.awssdk.services.route53.Route53ClientBuilder;
import software.amazon.awssdk.services.sts.model.AssumeRoleRequest;
import software.amazon.awssdk.services.sts.model.AssumeRoleResponse;
import software.amazon.awssdk.services.sts.model.Credentials;

public class AwsConnectionImpl implements AwsConnection {
	private @NonNull String description;
	
	private Region region;
	
	private String accessKey;
	private String secretKey;
	private String assumeRole;
	int assumeRoleSeconds;
	
	@Override
	public @NonNull String getDescription() { return description; }
	
	public AwsConnectionImpl(
			@NonNull String name,
			@NonNull Composite spec) throws SchemaException
	{
		String tmpDescription= spec.getOptionalString("description");
		if (tmpDescription == null) {
			tmpDescription= name;
		}
		this.description= tmpDescription;
		
		this.region= Region.of(spec.getRequiredString("region"));
		
		String tmpAccessKey= spec.getOptionalString("accessKey");
		String tmpSecretKey= spec.getOptionalString("secretKey");
		
		accessKey= tmpAccessKey;
		secretKey= tmpSecretKey;
		
		assumeRole= spec.getOptionalString("assumeRole");
		
		Integer assumeRoleMinutes= spec.getOptionalInteger("assumeRoleMinutes");
		if (assumeRoleMinutes == null) {
			assumeRoleMinutes= 60;
		}
		
		assumeRoleSeconds= assumeRoleMinutes * 60;
	}

	
	private AwsCredentialsProvider savedCredentialsProvider= null;
	
	/**
	 * getCredentialsProvider
	 * 
	 * Get the AWS credentials provider, after assuming any role specified at creation time.
	 * 
	 * @return							Credentials structure
	 */
	private @NonNull AwsCredentialsProvider getCredentialsProvider() {
		// Return the saved credential if present - assuming a role can take a while
		AwsCredentialsProvider credentialsProvider= savedCredentialsProvider;
		if (credentialsProvider == null) {
			if ((accessKey != null) && (secretKey != null)) {
				// If we got an access key / secret key use that.  This is needed if we're not
				// running inside an AWS environment.
				AwsBasicCredentials basicCredentials= AwsBasicCredentials.create(accessKey, secretKey);
				credentialsProvider= StaticCredentialsProvider.create(basicCredentials);
			} else {
				// Otherwise use the instance profile, which just picks up what we innately have
				// because of the profile we're running under.
				credentialsProvider= InstanceProfileCredentialsProvider.create();
			}
			
			// If we have to assume a role we have to go through this whole dance.
			if (assumeRole != null) {
				StsClient stsClient= StsClient.builder()
						.region(region)
						.credentialsProvider(credentialsProvider)
						.build();
	
				AssumeRoleRequest assumeRoleRequest= AssumeRoleRequest.builder()
						.durationSeconds(assumeRoleSeconds)
						.roleArn(assumeRole)
						.roleSessionName("SMBTrack-EventD")
						.build();
				
				AssumeRoleResponse assumeRoleResponse= stsClient.assumeRole(assumeRoleRequest);
				Credentials assumedCredentials= assumeRoleResponse.credentials();
				
				AwsSessionCredentials sessionCredentials= AwsSessionCredentials.create(
						assumedCredentials.accessKeyId(),
						assumedCredentials.secretAccessKey(),
						assumedCredentials.sessionToken());
				
				credentialsProvider= StaticCredentialsProvider.create(sessionCredentials);
			}
			
			if (credentialsProvider == null) {
				throw new RuntimeException("Logic error - credentials provider is null");
			}
			
			// Save for re-use.  We don't worry about roles expiring because AwsConnection
			// instances are created at the time of use.  The SQS classes have that problem, but
			// we don't.
			savedCredentialsProvider= credentialsProvider;
		}
		
		return credentialsProvider;
	}
	
	@Override
	public @NonNull Route53Client buildRoute53Client() {
		Route53ClientBuilder builder= Route53Client.builder();
		
		try {
			URI endpoint= new URI("https://route53.amazonaws.com");
			
			builder.region(region);
			builder.endpointOverride(endpoint);
			builder.credentialsProvider(getCredentialsProvider());
	
			Route53Client client= builder.build();
			if (client == null) {
				throw new RuntimeException("Unexpected null return from API builder");
			}
		
			return client;
		} catch (URISyntaxException e) {
			throw new RuntimeException("Unable to parse known good URI?");
		}
	}
}
