/****************************************************************************
 * Copyright 2022 Teaglu, LLC                                               *
 *                                                                          *
 * Licensed under the Apache License, Version 2.0 (the "License");          *
 * you may not use this file except in compliance with the License.         *
 * You may obtain a copy of the License at                                  *
 *                                                                          *
 *   http://www.apache.org/licenses/LICENSE-2.0                             *
 *                                                                          *
 * Unless required by applicable law or agreed to in writing, software      *
 * distributed under the License is distributed on an "AS IS" BASIS,        *
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. *
 * See the License for the specific language governing permissions and      *
 * limitations under the License.                                           *
 ****************************************************************************/

package com.teaglu.dnsalias.alert.impl;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

import org.eclipse.jdt.annotation.NonNull;
import org.eclipse.jdt.annotation.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.teaglu.composite.Composite;
import com.teaglu.composite.exception.FormatException;
import com.teaglu.composite.exception.MissingValueException;
import com.teaglu.composite.exception.SchemaException;
import com.teaglu.dnsalias.alert.AlertCategory;
import com.teaglu.dnsalias.alert.AlertSink;

import jakarta.mail.Authenticator;
import jakarta.mail.MessagingException;
import jakarta.mail.Multipart;
import jakarta.mail.PasswordAuthentication;
import jakarta.mail.Session;
import jakarta.mail.Transport;
import jakarta.mail.internet.AddressException;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeBodyPart;
import jakarta.mail.internet.MimeMessage;
import jakarta.mail.internet.MimeMessage.RecipientType;
import jakarta.mail.internet.MimeMultipart;

/**
 * SmtpAlertSink
 * 
 * Alert sink to send everything as an SMTP message
 */
public class SmtpAlertSink implements AlertSink {
	private static final Logger log= LoggerFactory.getLogger(SmtpAlertSink.class);
	
	private @NonNull String host;

	// Configurable in some other areas, but not really used any more
	private int port;
	private boolean tls;
	
	private String username;
	private String password;
	
	private @NonNull InternetAddress fromAddress;
	private @NonNull List<InternetAddress> toAddresses= new ArrayList<>();
	
	private SmtpAlertSink(
			@NonNull Composite spec) throws SchemaException
	{
		host= spec.getRequiredString("host");
		username= spec.getOptionalString("username");
		password= spec.getOptionalString("password");
		
		tls= spec.getOptionalBoolean("tls", true);
		
		Integer portInt= spec.getOptionalInteger("port");
		port= (portInt != null) ? portInt : 587;
		
		// Go ahead and parse the addresses here so format errors get thrown on startup
		try {
			fromAddress= new InternetAddress(spec.getRequiredString("from"));
		} catch (AddressException e) {
			throw new FormatException("Unable to parse from address");
		}

		Iterable<@NonNull String> addresses= spec.getRequiredStringArray("to");
		for (String address : addresses) {
			try {
				toAddresses.add(new InternetAddress(address));
			} catch (AddressException e) {
				throw new FormatException("Unable to parse to address " + address);
			}
		}
		if (toAddresses.isEmpty()) {
			throw new MissingValueException("At least one TO address is required");
		}
	}
	
	public static @NonNull AlertSink Create(
			@NonNull Composite spec) throws SchemaException
	{
		return new SmtpAlertSink(spec);
	}
	
	private class PasswordAuthenticator extends Authenticator {
		private PasswordAuthentication auth;
		
		private PasswordAuthenticator(String username, String password) {
			auth= new PasswordAuthentication(username, password);
		}
		
		@Override
		protected PasswordAuthentication getPasswordAuthentication() {
			return auth;
		}
	}
	
	@Override
	public void sendAlert(
			@NonNull AlertCategory category,
			@NonNull String message,
			@Nullable Exception exception)
	{
		try {
			Properties props= new Properties();
			
			props.setProperty("mail.smtp.host", host);
			props.setProperty("mail.smtp.port", Integer.toString(port));
			props.setProperty("mail.smtp.connectionTimeout", "20000");
			props.setProperty("mail.smtp.timeout", "20000");
			props.setProperty("mail.smtp.sendPartial", "true");
			
			if (tls) {
				props.setProperty("mail.smtp.starttls.enable", "true");
				props.setProperty("mail.smtp.ssl.protocols", "TLSv1 TLSv1.1 TLSv1.2 TLSv1.3");
			}
			
			Session session= null;
			
			if ((username != null) && (password != null)) {
				session= Session.getInstance(
						props, new PasswordAuthenticator(username, password));
				
				props.setProperty("mail.smtp.auth", "true");
			} else {
				session= Session.getInstance(props);
			}
		
			MimeMessage mail= new MimeMessage(session);
			mail.setFrom(fromAddress);
			
			for (InternetAddress address : toAddresses) {
				mail.addRecipient(RecipientType.TO, address);
			}
			mail.setSubject("DNS Alias " + category.toString());
			
			Multipart multipart= new MimeMultipart();
			
			StringBuilder body= new StringBuilder();
			body.append(message);
			body.append("\n");
			
			if (exception != null) {
				addException(exception, body);
			}
			
			MimeBodyPart textPart= new MimeBodyPart();
			textPart.setText(body.toString(), StandardCharsets.UTF_8.name());
			textPart.setHeader("Content-Type", "text/plain");
			multipart.addBodyPart(textPart);
			
			mail.setContent(multipart);
			mail.saveChanges();
		
			Transport.send(mail);
		} catch (MessagingException e) {
			log.error("Error sending alert", e);
			log.error("Original alert: " + message, exception);
		}
	}

	public static void addException(
			@NonNull Throwable e,
			@NonNull StringBuilder body)
	{
		String exceptionClass= e.getClass().getName();
		
		String message= e.getMessage();
		if (message == null) {
			message= "Exception without message: " + exceptionClass;
		}

		body.append("\r\n");
		body.append("Message: ");
		body.append(message);
		body.append("\r\n");
		body.append("Exception: ");
		body.append(exceptionClass);
		body.append("\r\n");
		body.append("Trace:\r\n");
		
		StackTraceElement trace[]= e.getStackTrace();
		for (int lvl= 0; lvl < trace.length; lvl++) {
			body.append("  Frame ");
			body.append(lvl);
			body.append(":\r\n");
			
			StackTraceElement frame= trace[lvl];
			
			body.append("    Class: ");
			body.append(frame.getClassName());
			body.append("\r\n");
			body.append("    Method: ");
			body.append(frame.getMethodName());
			body.append("\r\n");
			body.append("    Line: ");
			body.append(frame.getLineNumber());
			body.append("\r\n");
		}

		Throwable cause= e.getCause();
		if (cause != null) {
			body.append("\r\nCaused By:\r\n");
			addException(cause, body);
		}
	}
}
