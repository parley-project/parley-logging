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

import java.util.ArrayList;
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
 * Levels: no application has to set one. When no {@value BaseObject#PROPERTY_LOG_LEVEL} system
 * property covers a logger's name, the logger lets WARN and ERROR through ({@link #DEFAULT_LEVEL}).
 * <ul>
 * <li>A logger named under {@code us.bringardner.} is Parley's: its messages come through BaseObject.
 * When a {@value BaseObject#PROPERTY_LOG_LEVEL} system property covers its name
 * ({@code parley.log.level.pop3.server.Pop3Server}, then {@code parley.log.level.pop3.server},
 * {@code parley.log.level.pop3}, then {@code parley.log.level}), BaseObject has already decided with
 * it and the logger lets everything through. When none does, the logger lets WARN and ERROR through.
 * The settings are looked at each time a message below WARN arrives, so one made after the logger
 * (a server that loads its properties file when it starts) counts.</li>
 * <li>Any other logger (the JDK's own, JMX's, HttpClient's ...) uses the nearest
 * {@value BaseObject#PROPERTY_LOG_LEVEL} system property for its name
 * ({@code parley.log.level.javax.management}, then {@code parley.log.level.javax}, then
 * {@code parley.log.level}), WARN when none is set, so they don't flood the log with their DEBUG
 * and TRACE messages. These loggers are made once per name; their settings are read then.</li>
 * </ul>
 * {@link ParleyLogger#setLevel(LogLevel)} on a logger wins over both.
 * <p>
 * The logger's level is the only one a System.Logger knows: a level an object was given with
 * BaseObject.setLogLevel, or a {@value BaseObject#PROPERTY_LOG_LEVEL} in an object's own
 * configuration, decides what BaseObject passes on, and the logger then decides again. To see a
 * Parley object's INFO or DEBUG messages, set a {@value BaseObject#PROPERTY_LOG_LEVEL} system
 * property that covers it.
 */
public class ParleyLoggerFinder extends System.LoggerFinder {

	/** The loggers named under this are Parley's: BaseObject decides when a level setting covers the name. */
	public static final String PARLEY_PREFIX = "us.bringardner.";

	/**
	 * The level of a logger when no {@value BaseObject#PROPERTY_LOG_LEVEL} system property covers its name.
	 * @since 2.0.0
	 */
	public static final LogLevel DEFAULT_LEVEL = LogLevel.WARN;

	private final ConcurrentHashMap<String, ParleyLogger> loggers = new ConcurrentHashMap<>();

	@Override
	public System.Logger getLogger(String name, Module module) {
		String key = name == null ? "" : name;
		ParleyLogger ret = loggers.get(key);
		if( ret == null ) {
			//  Not computeIfAbsent: opening the log file may make the JDK ask for another logger
			ParleyLogger made = key.startsWith(PARLEY_PREFIX)
					? new ParleyNameLogger(key, settingKeys(key))
					: new ParleyLogger(key, levelFor(key));
			ret = loggers.putIfAbsent(key, made);
			if( ret == null ) {
				ret = made;
			}
		}
		return ret;
	}

	/**
	 * @param name a logger's name
	 * @return the level a logger of that name lets through now: for a Parley logger, DEBUG (everything)
	 *         when a {@value BaseObject#PROPERTY_LOG_LEVEL} system property covers its name; for any
	 *         other, the nearest such property's level; {@link #DEFAULT_LEVEL} when none is set
	 */
	public static LogLevel levelFor(String name) {
		String v = nearestSetting(settingKeys(name));
		if( name != null && name.startsWith(PARLEY_PREFIX) ) {
			return v == null ? DEFAULT_LEVEL : LogLevel.DEBUG;
		}
		return LogLevel.parse(v, DEFAULT_LEVEL);
	}

	/** The system properties that can set the level of a logger, the nearest first, parley.log.level last. */
	private static String[] settingKeys(String name) {
		ArrayList<String> ret = new ArrayList<>();
		String n = BaseObject.levelName(name);
		while( !n.isEmpty() ) {
			ret.add(BaseObject.PROPERTY_LOG_LEVEL + "." + n);
			int dot = n.lastIndexOf('.');
			n = dot < 0 ? "" : n.substring(0, dot);
		}
		ret.add(BaseObject.PROPERTY_LOG_LEVEL);
		return ret.toArray(new String[0]);
	}

	/** The value of the first of these system properties that is set (not empty); null for none. */
	private static String nearestSetting(String[] keys) {
		for(String key : keys) {
			String v = System.getProperty(key);
			if( v != null && !v.trim().isEmpty() ) {
				return v;
			}
		}
		return null;
	}

	/**
	 * A Parley logger (named under {@value #PARLEY_PREFIX}): everything goes through when a
	 * {@value BaseObject#PROPERTY_LOG_LEVEL} system property covers its name (BaseObject has decided
	 * with it), WARN and ERROR when none does. setLevel wins over both.
	 */
	private static final class ParleyNameLogger extends ParleyLogger {
		private final String[] keys;
		private volatile boolean levelSet;

		ParleyNameLogger(String name, String[] keys) {
			super(name, LogLevel.DEBUG);
			this.keys = keys;
			//  (the constructor's own setLevel is not someone setting a level)
			this.levelSet = false;
		}

		@Override
		public void setLevel(LogLevel level) {
			super.setLevel(level);
			levelSet = true;
		}

		@Override
		public LogLevel getLevel() {
			return levelSet || nearestSetting(keys) != null ? super.getLevel() : DEFAULT_LEVEL;
		}

		@Override
		public boolean isLoggable(Level target) {
			if( levelSet ) {
				return super.isLoggable(target);
			}
			//  WARN and ERROR always; the settings are only looked at for the rest
			return DEFAULT_LEVEL.allows(LogLevel.of(target)) || (nearestSetting(keys) != null && super.isLoggable(target));
		}
	}
}
