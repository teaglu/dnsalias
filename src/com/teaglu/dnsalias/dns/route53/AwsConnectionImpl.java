package com.teaglu.dnsalias.dns.route53;

import java.net.URI;
import java.net.URISyntaxException;

import org.eclipse.jdt.annotation.NonNull;
import com.teaglu.composite.Composite;
import com.teaglu.composite.exception.MissingValueException;
import com.teaglu.composite.exception.RangeException;
import com.teaglu.composite.exception.SchemaException;
import com.teaglu.configure.exception.ConfigException;
import com.teaglu.configure.secret.SecretProvider;
import com.teaglu.configure.secret.SecretReplacer;
import com.teaglu.configure.secret.replacer.AtIdSecretReplacer;

import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.awssdk.auth.credentials.AwsSessionCredentials;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.sts.StsClient;
import software.amazon.awssdk.services.route53.Route53Client;
import software.amazon.awssdk.services.route53.Route53ClientBuilder;
import software.amazon.awssdk.services.sts.model.AssumeRoleRequest;
import software.amazon.awssdk.services.sts.model.AssumeRoleResponse;
import software.amazon.awssdk.services.sts.model.Credentials;

/**
 * AwsConnectionImpl
 * 
 * Implementation of AwsConnection
 *
 */
public class AwsConnectionImpl implements AwsConnection {
	// Region to operate in
	private Region region;
	
	private boolean endpointOverride= false;
	
	// Access and secret key for credentials
	private String accessKey;
	private String secretKey;
	
	// Role to assume for operations
	private String assumeRole;
	
	// How many seconds to assume the role
	private int assumeRoleSeconds;
	
	// Zone objects aren't meant to be stored long term, and are bound to the client object
	// they were created with.  This is how many milliseconds of slack we give ourselves for
	// operations before creating a new STS session to be safe.
	private static final long ASSUME_ROLE_SLACK= 300_000;
	
	public AwsConnectionImpl(
			@NonNull String name,
			@NonNull Composite spec,
			@NonNull SecretProvider secretProvider) throws SchemaException, ConfigException
	{
		// You would think this would blow up if you put in us-central-8 or something,
		// but I don't think that's compiled in which sort of makes sense.  From what I
		// remember you just get weird DNS errors.
		this.region= Region.of(spec.getRequiredString("region"));
		
		// I don't remember why this was here, but I'm having weird API errors when running as
		// an ECS service, so I'm making this configurable.  It doesn't seem to be necessary
		// any more, so it might have been carried over from when we were using the V1 SDK.
		this.endpointOverride= spec.getOptionalBoolean("endpointOverride", false);
		
		String tmpAccessKey= spec.getOptionalString("accessKey");
		String tmpSecretKey= spec.getOptionalString("secretKey");

		// Better to give a message on config than to do something confusing
		if ((tmpAccessKey != null) && (tmpSecretKey == null)) {
			throw new MissingValueException("secretKey");
		}
		if ((tmpSecretKey != null) && (tmpAccessKey == null)) {
			throw new MissingValueException("accessKey");
		}
		
		if ((tmpAccessKey != null) && (tmpSecretKey != null)) {
			SecretReplacer secretReplacer= AtIdSecretReplacer.Create(secretProvider);
			if (tmpAccessKey != null) {
				tmpAccessKey= secretReplacer.replace(tmpAccessKey);
			}
			if (tmpSecretKey != null) {
				tmpSecretKey= secretReplacer.replace(tmpSecretKey);
			}
		}
		
		// We just used tmp variables because the compiler can't track nullability in
		// class variables, because there might be another thread.
		accessKey= tmpAccessKey;
		secretKey= tmpSecretKey;
		
		// No reason for the role to be secret
		assumeRole= spec.getOptionalString("assumeRole");
		
		Integer assumeRoleMinutes= spec.getOptionalInteger("assumeRoleMinutes");
		if (assumeRoleMinutes == null) {
			assumeRoleMinutes= 60;
		} else if (assumeRoleMinutes < 15) {
			// AWS limits this to 15 minutes for some reason
			throw new RangeException("Assume role minutes is limited by AWS to >= 15 minutes");
		}
		
		assumeRoleSeconds= assumeRoleMinutes * 60;
	}

	// Saved credentials so we don't create STS sessions over and over
	private AwsCredentialsProvider savedCredentialsProvider= null;

	// This is the system time when the saved credentials expire
	private long savedCredentialsExpire= 0;
	
	/**
	 * getCredentialsProvider
	 * 
	 * Get the AWS credentials provider, after assuming any role specified at creation time.
	 * 
	 * @return							Credentials structure
	 */
	private @NonNull AwsCredentialsProvider getCredentialsProvider() {
		if (savedCredentialsProvider != null) {
			if (savedCredentialsExpire > 0) {
				if (System.currentTimeMillis() > savedCredentialsExpire) {
					savedCredentialsProvider= null;
				}
			}
		}
		
		// Return the saved credential if present - assuming a role can take a while
		AwsCredentialsProvider credentialsProvider= savedCredentialsProvider;
		
		if (credentialsProvider == null) {
			// I'm not sure exactly when the clock starts on the STS role, so assume it starts
			// before we ask to be on the safe side.
			long credentialsStart= System.currentTimeMillis();
			
			if ((accessKey != null) && (secretKey != null)) {
				// If we got an access key / secret key use that.  This is needed if we're not
				// running inside an AWS environment.
				AwsBasicCredentials basicCredentials= AwsBasicCredentials.create(accessKey, secretKey);
				credentialsProvider= StaticCredentialsProvider.create(basicCredentials);
			} else {
				// Otherwise use the instance profile, which just picks up what we innately have
				// because of the profile we're running under.
				//credentialsProvider= InstanceProfileCredentialsProvider.create();
				credentialsProvider= DefaultCredentialsProvider.create();
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
						.roleSessionName("DNS-Alias")
						.build();
				
				AssumeRoleResponse assumeRoleResponse= stsClient.assumeRole(assumeRoleRequest);
				Credentials assumedCredentials= assumeRoleResponse.credentials();
				
				AwsSessionCredentials sessionCredentials= AwsSessionCredentials.create(
						assumedCredentials.accessKeyId(),
						assumedCredentials.secretAccessKey(),
						assumedCredentials.sessionToken());
				
				credentialsProvider= StaticCredentialsProvider.create(sessionCredentials);
				
				// Record when the role expires, so that if someone asks for credentials again
				// after the expire time we'll regenerate everything.  Otherwise someone can
				// get an error because the STS session expired
				savedCredentialsExpire= credentialsStart +
						(assumeRoleSeconds * 1000) - ASSUME_ROLE_SLACK;
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
			builder.region(region);
			if (endpointOverride) {
				URI endpoint= new URI("https://route53.amazonaws.com");
				builder.endpointOverride(endpoint);
			}
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
