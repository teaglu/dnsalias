package com.teaglu.dnsalias.util;

import org.eclipse.jdt.annotation.NonNull;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

/**
 * ExceptionSerializer
 *
 * Utility class to serialize an exception (or error) to a JSON object
 */
public class ExceptionSerializer {
	/**
	 * serialize
	 * 
	 * @param exception					Exception / error
	 * @return
	 */
	public static @NonNull JsonObject serialize(
			@NonNull Throwable exception)
	{
		JsonObject obj= new JsonObject();
		
		String exceptionClass= exception.getClass().getSimpleName();
		
		String message= exception.getMessage();
		if (message == null) {
			message= "Exception without message: " + exceptionClass;
		}
		
		obj.addProperty("message", message);
		obj.addProperty("exceptionClass", exceptionClass);
		
		JsonArray traceArray= new JsonArray();
		
		StackTraceElement trace[]= exception.getStackTrace();
		for (int lvl= 0; lvl < trace.length; lvl++) {
			StackTraceElement frame= trace[lvl];
			
			JsonObject frameObj= new JsonObject();
			frameObj.addProperty("className", frame.getClassName());
			frameObj.addProperty("methodName", frame.getMethodName());
			frameObj.addProperty("lineNumber", frame.getLineNumber());
			
			traceArray.add(frameObj);
		}

		obj.add("stackTrace", traceArray);

		Throwable cause= exception.getCause();
		if (cause != null) {
			obj.add("cause", serialize(cause));
		}
		
		return obj;
	}
}
