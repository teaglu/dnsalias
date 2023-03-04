module com.teaglu.dnsalias {
	// Non-null annotations
	requires org.eclipse.jdt.annotation;

	// Composite and configuration libraries
	requires com.teaglu.composite;
	requires com.teaglu.configure;

	// AWS API stuff
	requires software.amazon.awssdk.core;
	requires software.amazon.awssdk.regions;
	requires software.amazon.awssdk.auth;
	requires software.amazon.awssdk.services.sts;
	requires software.amazon.awssdk.services.route53;

	// Logging library
	requires org.slf4j;
	
	// DNS resolution library
	requires org.dnsjava;
	
	// For sending alert emails
	requires jakarta.mail;
	requires aws.lambda.java.core;
	requires com.google.gson;
}