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

package com.teaglu.dnsalias.alert;

import org.eclipse.jdt.annotation.NonNull;

import com.teaglu.composite.Composite;
import com.teaglu.composite.exception.SchemaException;
import com.teaglu.composite.exception.UndefinedOptionException;
import com.teaglu.dnsalias.alert.impl.ConsoleAlertSink;
import com.teaglu.dnsalias.alert.impl.SmtpAlertSink;

/**
 * AlertSinkFactory
 *
 * Factory to build alert sinks
 */
public final class AlertSinkFactory {
	private static class InstanceHolder {
		private static @NonNull AlertSinkFactory INSTANCE= new AlertSinkFactory();
	}
	
	public static @NonNull AlertSinkFactory getInstance() {
		return InstanceHolder.INSTANCE;
	}
	
	private AlertSinkFactory() {}
	
	public @NonNull AlertSink create(
			@NonNull Composite spec) throws SchemaException
	{
		String type= spec.getRequiredString("type");
		switch (type) {
		case "console":
			return ConsoleAlertSink.Create();
			
		case "smtp":
			return SmtpAlertSink.Create(spec);
			
		default:
			throw new UndefinedOptionException("Alert type " + type + " not known");
		}
	}
}
