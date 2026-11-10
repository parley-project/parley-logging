/**
 * Copyright 1998-2026 Tony Bringardner
 *
 *   Licensed under the Apache License, Version 2.0 (the "License");
 *   you may not use this file except in compliance with the License.
 *   You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 *   Unless required by applicable law or agreed to in writing, software
 *   distributed under the License is distributed on an "AS IS" BASIS,
 *   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *   See the License for the specific language governing permissions and
 *   limitations under the License.
 *
 *	@author Tony Bringardner
 */
package us.bringardner.parley.logging;

import java.util.concurrent.ConcurrentHashMap;

import us.bringardner.parley.core.BaseObject;
import us.bringardner.parley.core.LogLevel;

/**
 * The System.LoggerFinder that makes {@link ParleyLogger} the JVM's System.Logger. The JDK finds it
 * (META-INF/services) when it is on the application's class path or module path, through the system
 * class loader; a copy in a web application's WEB-INF/lib is not seen.
 * <p>
 * Only applications include parley-logging (fsh, parley-mail-admin's servers ...), never library code:
 * a provider takes over all System.Logger output in its JVM, and with two providers the first on the
 * class path wins.
 * <p>
 * Levels:
 * <ul>
 * <li>A logger named under {@code us.bringardner.} lets everything through: Parley code logs through
 * BaseObject, whose level (setLogLevel, or the {@value BaseObject#PROPERTY_LOG_LEVEL} settings) has
 * already decided.</li>
 * <li>Any other logger (the JDK's own, JMX's, HttpClient's ...) uses the nearest
 * {@value BaseObject#PROPERTY_LOG_LEVEL} system property for its name
 * ({@code parley.log.level.javax.management}, then {@code parley.log.level.javax}, then
 * {@code parley.log.level}), WARN when none is set, so they don't flood the log with their DEBUG
 * and TRACE messages.</li>
 * </ul>
 * Loggers are made once per name; their settings are read then.
 */
public class ParleyLoggerFinder extends System.LoggerFinder {

	/** The loggers named under this let everything through (BaseObject has decided). */
	public static final String PARLEY_PREFIX = "us.bringardner.";

	/** The level of a logger outside {@value #PARLEY_PREFIX} when no setting gives one. */
	public static final LogLevel DEFAULT_OTHER_LEVEL = LogLevel.WARN;

	private final ConcurrentHashMap<String, ParleyLogger> loggers = new ConcurrentHashMap<>();

	@Override
	public System.Logger getLogger(String name, Module module) {
		String key = name == null ? "" : name;
		ParleyLogger ret = loggers.get(key);
		if( ret == null ) {
			//  Not computeIfAbsent: opening the log file may make the JDK ask for another logger
			ParleyLogger made = new ParleyLogger(key, levelFor(key));
			ret = loggers.putIfAbsent(key, made);
			if( ret == null ) {
				ret = made;
			}
		}
		return ret;
	}

	/**
	 * @param name a logger's name
	 * @return the level a logger of that name is made with
	 */
	public static LogLevel levelFor(String name) {
		if( name != null && name.startsWith(PARLEY_PREFIX) ) {
			return LogLevel.DEBUG;
		}
		String n = BaseObject.levelName(name);
		while( !n.isEmpty() ) {
			String v = System.getProperty(BaseObject.PROPERTY_LOG_LEVEL + "." + n);
			if( v != null && !v.trim().isEmpty() ) {
				return LogLevel.parse(v, DEFAULT_OTHER_LEVEL);
			}
			int dot = n.lastIndexOf('.');
			n = dot < 0 ? "" : n.substring(0, dot);
		}
		return LogLevel.parse(System.getProperty(BaseObject.PROPERTY_LOG_LEVEL), DEFAULT_OTHER_LEVEL);
	}
}
