package com.hedera.services.test.spec.infrastructure.providers.ops.schedule;

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

import com.hedera.services.test.spec.HapiSpecOperation;
import com.hedera.services.test.spec.infrastructure.OpProvider;
import com.hedera.services.test.spec.infrastructure.providers.names.RegistrySourcedNameProvider;
import com.hederahashgraph.api.proto.java.ResponseCodeEnum;
import com.hederahashgraph.api.proto.java.ScheduleID;
import com.hederahashgraph.api.proto.java.TokenID;

import java.util.Optional;

import static com.hedera.services.test.spec.infrastructure.providers.ops.schedule.RandomSchedule.ADMIN_KEY;
import static com.hedera.services.test.spec.transactions.TxnVerbs.scheduleDelete;
import static com.hedera.services.test.spec.transactions.TxnVerbs.tokenDelete;
import static com.hedera.services.test.spec.suites.HapiApiSuite.DEFAULT_PAYER;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_SCHEDULE_ID;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_SIGNATURE;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.SCHEDULE_IS_IMMUTABLE;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.TOKEN_IS_IMMUTABLE;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.TOKEN_WAS_DELETED;

public class RandomScheduleDeletion implements OpProvider {
	private final RegistrySourcedNameProvider<ScheduleID> schedules;

	private final ResponseCodeEnum[] permissibleOutcomes = standardOutcomesAnd(
			SCHEDULE_IS_IMMUTABLE, INVALID_SCHEDULE_ID, INVALID_SIGNATURE
	);

	public RandomScheduleDeletion(RegistrySourcedNameProvider<ScheduleID> schedules) {
		this.schedules = schedules;
	}

	@Override
	public Optional<HapiSpecOperation> get() {
		var target = schedules.getQualifying();
		if (target.isEmpty()) {
			return Optional.empty();
		}

		var op = scheduleDelete(target.get())
				.hasPrecheckFrom(STANDARD_PERMISSIBLE_PRECHECKS)
				.hasKnownStatusFrom(permissibleOutcomes)
				.signedBy(ADMIN_KEY);
		return Optional.of(op);
	}
}
