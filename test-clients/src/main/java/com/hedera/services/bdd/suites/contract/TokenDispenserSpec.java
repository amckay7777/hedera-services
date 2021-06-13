package com.hedera.services.bdd.suites.contract;

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

import com.google.common.primitives.Ints;
import com.google.common.primitives.Longs;
import com.hedera.services.bdd.spec.HapiApiSpec;
import com.hedera.services.bdd.spec.infrastructure.meta.ContractResources;
import com.hedera.services.bdd.suites.HapiApiSuite;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.math.BigInteger;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static com.hedera.services.bdd.spec.HapiApiSpec.defaultHapiSpec;
import static com.hedera.services.bdd.spec.assertions.AssertUtils.inOrder;
import static com.hedera.services.bdd.spec.assertions.ContractFnResultAsserts.isContractWith;
import static com.hedera.services.bdd.spec.assertions.ContractFnResultAsserts.isLiteralResult;
import static com.hedera.services.bdd.spec.assertions.ContractFnResultAsserts.resultWith;
import static com.hedera.services.bdd.spec.assertions.ContractInfoAsserts.contractWith;
import static com.hedera.services.bdd.spec.assertions.TransactionRecordAsserts.recordWith;
import static com.hedera.services.bdd.spec.keys.KeyFactory.KeyType.THRESHOLD;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getContractInfo;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getTxnRecord;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.contractCall;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.contractCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.fileCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.tokenAssociate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.tokenCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.tokenUpdate;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.contractListWithPropertiesInheritedFrom;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.newKeyNamed;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.sourcing;
import static java.lang.System.arraycopy;

public class TokenDispenserSpec extends HapiApiSuite {
	private static final Logger log = LogManager.getLogger(TokenDispenserSpec.class);
	private static final long depositAmount = 1000;

	public static void main(String... args) {
		/* Has a static initializer whose behavior seems influenced by initialization of ForkJoinPool#commonPool. */
		new org.ethereum.crypto.HashUtil();

		new TokenDispenserSpec().runSuiteSync();
	}

	@Override
	protected List<HapiApiSpec> getSpecsInSuite() {
		return List.of(new HapiApiSpec[] {
						tokenDispenserPoc(),
				}
		);
	}

	HapiApiSpec tokenDispenserPoc() {
		final var bytecode = "bytecode";
		final var tokenDispenser = "contract";
		final var token = "token";
		final var adminKey = "adminKey";

		final AtomicReference<byte[]> tokenSolidityAddr = new AtomicReference<>();

		final var supplyToDispense = 100;
		final var purchaseTxn = "purchaseTxn";
		final long tinybarsToSpend = 10;

		return defaultHapiSpec("TokenDispenserPoc")
				.given(
						newKeyNamed(adminKey),
						cryptoCreate(TOKEN_TREASURY),
						tokenCreate(token)
								.adminKey(adminKey)
								.treasury(TOKEN_TREASURY)
								.initialSupply(supplyToDispense)
								.decimals(0)
								.onCreation(tId -> tokenSolidityAddr.set(
										asSolidityAddress((int) tId.getShardNum(), tId.getRealmNum(),
												tId.getTokenNum()))),
						fileCreate(bytecode)
								.path(ContractResources.TOKEN_DISPENSER_BYTECODE_PATH),
						sourcing(() ->
								contractCreate(
										tokenDispenser,
										ContractResources.CONSTRUCT_DISPENSOR_ABI,
										tokenSolidityAddr.get()
								)
										.bytecode(bytecode)),
						tokenAssociate(tokenDispenser, token),
						tokenUpdate(token).treasury(tokenDispenser)
				).when(
						contractCall(tokenDispenser, ContractResources.DISPENSE_ABI)
								.via(purchaseTxn)
								.gas(300_000L)
								.sending(tinybarsToSpend)
				).then(
						getTxnRecord(purchaseTxn).logged()
				);
	}

	public static byte[] asSolidityAddress(int shard, long realm, long num) {
		byte[] solidityAddress = new byte[20];

		arraycopy(Ints.toByteArray(shard), 0, solidityAddress, 0, 4);
		arraycopy(Longs.toByteArray(realm), 0, solidityAddress, 4, 8);
		arraycopy(Longs.toByteArray(num), 0, solidityAddress, 12, 8);

		return solidityAddress;
	}

	HapiApiSpec depositSuccess() {
		return defaultHapiSpec("DepositSuccess")
				.given(
						fileCreate("payableBytecode").path(ContractResources.PAYABLE_CONTRACT_BYTECODE_PATH),
						contractCreate("payableContract").bytecode("payableBytecode").adminKey(THRESHOLD)
				).when(
						contractCall("payableContract", ContractResources.DEPOSIT_ABI, depositAmount)
								.via("payTxn").sending(depositAmount)
				).then(
						getTxnRecord("payTxn")
								.hasPriority(recordWith().contractCallResult(
										resultWith().logs(inOrder()))));
	}


	HapiApiSpec vanillaSuccess() {
		return defaultHapiSpec("VanillaSuccess")
				.given(
						fileCreate("parentDelegateBytecode").path(ContractResources.DELEGATING_CONTRACT_BYTECODE_PATH),
						contractCreate("parentDelegate").bytecode("parentDelegateBytecode").adminKey(THRESHOLD),
						getContractInfo("parentDelegate").saveToRegistry("parentInfo")
				).when(
						contractCall("parentDelegate", ContractResources.CREATE_CHILD_ABI).via("createChildTxn"),
						contractCall("parentDelegate", ContractResources.GET_CHILD_RESULT_ABI).via("getChildResultTxn"),
						contractCall("parentDelegate", ContractResources.GET_CHILD_ADDRESS_ABI).via(
								"getChildAddressTxn")
				).then(
						getTxnRecord("createChildTxn")
								.saveCreatedContractListToRegistry("createChild")
								.logged(),
						getTxnRecord("getChildResultTxn")
								.hasPriority(recordWith().contractCallResult(
										resultWith().resultThruAbi(
												ContractResources.GET_CHILD_RESULT_ABI,
												isLiteralResult(new Object[] { BigInteger.valueOf(7L) })))),
						getTxnRecord("getChildAddressTxn")
								.hasPriority(recordWith().contractCallResult(
										resultWith()
												.resultThruAbi(
														ContractResources.GET_CHILD_ADDRESS_ABI,
														isContractWith(contractWith()
																.nonNullContractId()
																.propertiesInheritedFrom("parentInfo")))
												.logs(inOrder()))),
						contractListWithPropertiesInheritedFrom("createChildCallResult", 1, "parentInfo")
				);
	}

	@Override
	protected Logger getResultsLogger() {
		return log;
	}
}
