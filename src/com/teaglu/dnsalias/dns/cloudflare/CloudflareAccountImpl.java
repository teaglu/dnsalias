package com.teaglu.dnsalias.dns.cloudflare;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Map;

import org.eclipse.jdt.annotation.NonNull;
import org.eclipse.jdt.annotation.Nullable;

import com.google.gson.JsonObject;
import com.teaglu.composite.Composite;
import com.teaglu.composite.exception.SchemaException;
import com.teaglu.composite.json.JsonComposite;
import com.teaglu.configure.exception.ConfigException;
import com.teaglu.configure.secret.SecretProvider;
import com.teaglu.configure.secret.SecretReplacer;
import com.teaglu.configure.secret.replacer.AtIdSecretReplacer;
import com.teaglu.dnsalias.dns.exception.DnsApiException;

public class CloudflareAccountImpl implements CloudflareAccount {
	private @NonNull String apiToken;
	
	CloudflareAccountImpl(
			@NonNull Composite spec,
			@NonNull SecretProvider secretProvider) throws SchemaException, ConfigException
	{
		SecretReplacer secretReplacer= AtIdSecretReplacer.Create(secretProvider);
		
		this.apiToken= secretReplacer.replace(spec.getRequiredString("apiToken"));
	}
	
	@Override
	public @NonNull Iterable<@NonNull Composite> query(
			@NonNull String relativeUrl,
			@Nullable Map<@NonNull String, @Nullable String> parameters) throws IOException, DnsApiException
	{
		Composite response= call("GET", relativeUrl, parameters, null);
		
		try {
			checkResponse(response);			
			return response.getRequiredObjectArray("result");
		} catch (SchemaException schemaException) {
			throw new DnsApiException(
					"Unable to interpret API response", schemaException);
		}
	}
	
	public @NonNull Composite singleAction(
			@NonNull String verb,
			@NonNull String relativeUrl,
			@NonNull JsonObject body) throws IOException, DnsApiException
	{
		Composite response= call(verb, relativeUrl, null, body);
		checkResponse(response);
		
		try {
			return response.getRequiredObject("result");
		} catch (SchemaException schemaException) {
			throw new DnsApiException(
					"Unable to find result in API resopnse", schemaException);
		}
	}
	
	@Override
	public @NonNull Composite update(
			@NonNull String relativeUrl,
			@NonNull JsonObject body) throws IOException, DnsApiException
	{
		return singleAction("PATCH", relativeUrl, body);
	}
	
	@Override
	public @NonNull Composite create(
			@NonNull String relativeUrl,
			@NonNull JsonObject body) throws IOException, DnsApiException
	{
		return singleAction("POST", relativeUrl, body);
	}
	
	@Override
	public void delete(
			@NonNull String relativeUrl) throws IOException, DnsApiException
	{
		Composite response= call("DELETE", relativeUrl, null, null);
		checkResponse(response);
	}

	private void checkResponse(@NonNull Composite response) throws DnsApiException {
		try {
			if (!response.getRequiredBoolean("success")) {
				StringBuilder errorMessage= new StringBuilder("Failure in CloudFront API: ");
				
				Iterable<@NonNull Composite> errors= response.getRequiredObjectArray("errors");
				for (Composite error : errors) {
					errorMessage.append(error.getRequiredInteger("code"));
					errorMessage.append("/");
					errorMessage.append(error.getRequiredString("message"));
				}
				
				throw new DnsApiException(errorMessage.toString());
			}
		} catch (SchemaException schemaException) {
			throw new DnsApiException(
					"Unable to check response for error conditions: " + response.toString());
		}
	}
	
	public @NonNull Composite call(
			@NonNull String verb,
			@NonNull String relativeUrl,
			@Nullable Map<@NonNull String, @Nullable String> parameters,
			@Nullable JsonObject body) throws IOException, DnsApiException
	{
		Composite response= null;

		StringBuilder path= new StringBuilder("https://api.cloudflare.com/client/v4");
		path.append(relativeUrl);
		if (parameters != null) {
			boolean first= true;

			for (Map.Entry<@NonNull String, @Nullable String> parameter : parameters.entrySet()) {
				if (first) {
					path.append("?");
					first= false;
				} else {
					path.append("&");
				}
				
				path.append(URLEncoder.encode(parameter.getKey(), StandardCharsets.UTF_8));
				path.append("=");
				
				String value= parameter.getValue();
				if (value != null) {
					path.append(URLEncoder.encode(parameter.getValue(), StandardCharsets.UTF_8));
				}
			}
		}
		
		URL url= null;
		try {
			url= new URL(path.toString());
		} catch (MalformedURLException e) {
			throw new DnsApiException("URL build error");
		}

		HttpURLConnection connection= (HttpURLConnection)url.openConnection();
		try {
			connection.setRequestProperty("User-Agent", "Teaglu-DNS");
			connection.setRequestMethod(verb);
			
			connection.setRequestProperty("Authorization", "Bearer " + apiToken);
			
			connection.setDoInput(true);

			if (body != null) {
				byte[] uploadData= body.toString().getBytes(StandardCharsets.UTF_8);

				connection.setRequestProperty("Content-Type", "application/json");
				connection.setRequestProperty("Content-Length",
						String.valueOf(uploadData.length));
			
				connection.setDoOutput(true);
				connection.getOutputStream().write(uploadData);
			}

			int responseCode= connection.getResponseCode();
			if ((responseCode == 200) || (responseCode == 201)) {
				try (InputStreamReader isr= new InputStreamReader(
						connection.getInputStream(), StandardCharsets.UTF_8))
				{
					try {
						response= JsonComposite.Parse(isr);
					} catch (SchemaException se) {
						throw new DnsApiException("Feedback POST returned invalid data", se);
					}
				}
			} else {
				// Implementation doesn't really specify whether you get ErrorStream or
				// InputStream - the correct answer seems to be to check ErrorStream first.
				InputStream inputStream= connection.getErrorStream();
				if (inputStream == null) {
					try {
						inputStream= connection.getInputStream();
					} catch (IOException e) {
					}
				}
				
				StringBuilder responseText= new StringBuilder();
				if (inputStream != null) {
					try (InputStreamReader isr= new InputStreamReader(
							inputStream, StandardCharsets.UTF_8))
					{
						char[] responseBuffer= new char[2048];
						
						while (true) {
							int bytesRead= isr.read(responseBuffer);
							if (bytesRead == -1) {
								break;
							} else {
								responseText.append(responseBuffer, 0, bytesRead);
							}
						}
						
						System.err.println(responseText.toString());
					}
				}
				
				throw new DnsApiException("Feedback API returned status " + responseCode);
			}
		} finally {
			connection.disconnect();
		}
		
		return response;
	}
}
