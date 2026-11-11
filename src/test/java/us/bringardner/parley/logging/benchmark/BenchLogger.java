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
package us.bringardner.parley.logging.benchmark;

import java.util.function.Supplier;

/**
 * What the benchmarks call: the same few logging calls, made the way each kind of logger is used
 * ({@link LoggerFixture} has one implementation for each).
 */
interface BenchLogger {

	boolean isDebugEnabled();

	boolean isInfoEnabled();

	boolean isWarnEnabled();

	void debug(String msg);

	void debug(Supplier<String> msg);

	void info(String msg);

	void warn(String msg, Throwable error);
}
