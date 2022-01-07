package com.better.alarm.configuration

import com.better.alarm.logger.LoggerFactory
import org.koin.core.context.GlobalContext
import org.koin.core.parameter.ParametersDefinition
import org.koin.core.qualifier.Qualifier

/**
 * globalInject lazily given dependency
 * @param qualifier
 * - bean qualifier / optional
 * @param parameters
 * - injection parameters
 */
inline fun <reified T : Any> globalInject(
    qualifier: Qualifier? = null,
    noinline parameters: ParametersDefinition? = null
) = lazy { GlobalContext.get().get<T>(qualifier, parameters) }

inline fun <reified T : Any> globalGet(
    qualifier: Qualifier? = null,
    noinline parameters: ParametersDefinition? = null
) = GlobalContext.get().get<T>(qualifier, parameters)

fun globalLogger(tag: String) = lazy { GlobalContext.get().get<LoggerFactory>().createLogger(tag) }

/**
 * globalInject lazily given dependency
 * @param clazz
 */
fun <T : Any> globalInject(clazz: Class<T>) = lazy { GlobalContext.get().get(clazz = clazz.kotlin) }
