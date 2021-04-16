package com.hedera.services.test.spec.transactions.schedule;

/*-
 * ‌
 * Hedera Services Test Clients
 * ​
 * Copyright (C) 2018 - 2021 Hedera Hashgraph, LLC
 * ​
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * 
 *      http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 * ‍
 */

import com.hedera.services.test.spec.HapiApiSpec;
import com.hederahashgraph.api.proto.java.ScheduleInfo;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.Optional;

import static com.hedera.services.test.spec.HapiPropertySource.asScheduleString;
import static com.hedera.services.test.spec.queries.QueryVerbs.getScheduleInfo;

public class ScheduleFeeUtils {
	static final Logger log = LogManager.getLogger(ScheduleFeeUtils.class);

	static ScheduleInfo lookupInfo(HapiApiSpec spec, String schedule, boolean loggingOff) throws Throwable {
		var subOp = getScheduleInfo(schedule).noLogging();
		Optional<Throwable> error = subOp.execFor(spec);
		if (error.isPresent()) {
			if (!loggingOff) {
				var literal = asScheduleString(spec.registry().getScheduleId(schedule));
				log.warn("Unable to look up {}", literal, error.get());
			}
			throw error.get();
		}
		return subOp.getResponse().getScheduleGetInfo().getScheduleInfo();
	}
}
