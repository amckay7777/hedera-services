package com.hedera.services.test.spec.infrastructure.providers.ops.crypto;

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
import com.hedera.services.test.spec.infrastructure.providers.LookupUtils;
import com.hedera.services.test.spec.infrastructure.providers.names.RegistrySourcedNameProvider;
import com.hedera.services.test.spec.transactions.crypto.HapiCryptoDelete;
import com.hederahashgraph.api.proto.java.AccountID;
import com.hederahashgraph.api.proto.java.ResponseCodeEnum;

import java.util.List;
import java.util.Optional;

import static com.hedera.services.test.spec.transactions.TxnVerbs.cryptoDelete;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.ACCOUNT_DELETED;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_ACCOUNT_ID;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.TRANSACTION_REQUIRES_ZERO_TOKEN_BALANCES;
import static java.util.Collections.EMPTY_LIST;

public class RandomAccountDeletion implements OpProvider {
	private final RegistrySourcedNameProvider<AccountID> accounts;
	private final ResponseCodeEnum[] permissiblePrechecks = standardPrechecksAnd(
			ACCOUNT_DELETED,
			INVALID_ACCOUNT_ID
	);
	private final ResponseCodeEnum[] permissibleOutcomes = standardOutcomesAnd(
			ACCOUNT_DELETED,
			INVALID_ACCOUNT_ID,
			TRANSACTION_REQUIRES_ZERO_TOKEN_BALANCES
	);

	public RandomAccountDeletion(RegistrySourcedNameProvider<AccountID> accounts) {
		this.accounts = accounts;
	}

	@Override
	public List<HapiSpecOperation> suggestedInitializers() {
		return EMPTY_LIST;
	}

	@Override
	public Optional<HapiSpecOperation> get() {
		final var involved = LookupUtils.twoDistinct(accounts);
		if (involved.isEmpty()) {
			return Optional.empty();
		}
		HapiCryptoDelete op = cryptoDelete(involved.get().getKey())
				.purging()
				.transfer(involved.get().getValue())
				.hasPrecheckFrom(permissiblePrechecks)
				.hasKnownStatusFrom(permissibleOutcomes);
		return Optional.of(op);
	}
}
