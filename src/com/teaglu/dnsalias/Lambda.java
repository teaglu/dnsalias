package com.teaglu.dnsalias;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.util.ArrayList;
import java.util.List;

import org.eclipse.jdt.annotation.NonNull;
import org.eclipse.jdt.annotation.Nullable;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.LambdaLogger;
import com.amazonaws.services.lambda.runtime.RequestStreamHandler;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.teaglu.composite.Composite;
import com.teaglu.composite.json.JsonComposite;
import com.teaglu.configure.secret.SecretProvider;
import com.teaglu.configure.secret.SecretProviderFactory;
import com.teaglu.dnsalias.alert.AlertCategory;
import com.teaglu.dnsalias.alert.AlertSink;
import com.teaglu.dnsalias.singlerun.SingleRunExecutor;
import com.teaglu.dnsalias.singlerun.impl.ParallelSingleRunExecutor;
import com.teaglu.dnsalias.util.ExceptionSerializer;

/**
 * Lambda
 * 
 * Shim to run project as an AWS lambda function
 *
 */
public class Lambda implements RequestStreamHandler {
	// State during executor call
	private static class ExecutorState {
		private final List<Exception> exceptions= new ArrayList<>();
		
		private synchronized void addException(@NonNull Exception exception) {
			exceptions.add(exception);
		}
	}
	
	// We have to throw unchecked exceptions for Lambda to record a failure
	private static class AlertFailure extends RuntimeException {
		private static final long serialVersionUID = 1L;

		private AlertFailure(String message, Throwable cause) {
			super(message, cause);
		}
	}
	
	@Override
	public void handleRequest(
			InputStream inputStream,
			OutputStream outputStream,
			Context context) throws IOException
	{
		LambdaLogger logger= context.getLogger();
		
		JsonObject output= new JsonObject();
		
		boolean logOutput= true;
		boolean logAlerts= true;

		final ExecutorState exceptionState= new ExecutorState();
		
		try {
			Composite config= null;
			try (InputStreamReader reader= new InputStreamReader(inputStream)) {
				config= JsonComposite.Parse(reader);
				
				logOutput= config.getOptionalBoolean("logOutput", true);
			}
			
			SecretProvider secretProvider= SecretProviderFactory.
					getInstance().
					createFromEnvironment();	
			
			JsonArray alerts= new JsonArray();
			
			AlertSink alertSink= new AlertSink() {
				@Override
				public void sendAlert(
						@NonNull AlertCategory category,
						@NonNull String message,
						@Nullable Exception exception)
				{
					JsonObject alert= new JsonObject();
					alert.addProperty("category", category.name());
					alert.addProperty("message", message);
					if (exception != null) {
						alert.add("exception", ExceptionSerializer.serialize(exception));
					}
					
					synchronized (alerts) {
						alerts.add(alert);
					}
					
					if (logAlerts) {
						StringBuilder logBuild= new StringBuilder("ALERT: ");
						logBuild.append("[");
						logBuild.append(category.name());
						logBuild.append("] ");
						logBuild.append(message);
						logBuild.append("\n");
						
						logger.log(logBuild.toString());
					}
					
					if (exception != null) {
						exceptionState.addException(exception);
					}
				}
			};
			
			SingleRunExecutor executor= ParallelSingleRunExecutor.Create();
			executor.run(config, secretProvider, alertSink);
			
			output.addProperty("success", true);
			output.add("alerts", alerts);
		} catch (Exception|Error exception) {
			output.addProperty("success", false);
			output.add("exception", ExceptionSerializer.serialize(exception));
		}
		
		try (OutputStreamWriter writer= new OutputStreamWriter(outputStream)) {
			Gson gson= new GsonBuilder().
					setPrettyPrinting().
					create();
			
			gson.toJson(output, writer);
		}
		
		if (logOutput) {
			logger.log("OUTPUT: " + output.toString() + "\n");
		}
		
		if (!exceptionState.exceptions.isEmpty()) {
			throw new AlertFailure(
					"One or more processors threw an alert with an attached exception",
					exceptionState.exceptions.get(0));
		}
	}
}
