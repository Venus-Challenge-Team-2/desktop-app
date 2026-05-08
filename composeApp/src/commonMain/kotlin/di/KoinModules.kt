package di

import com.leekleak.venusmonitor.HelperMQTT
import org.koin.core.module.Module
import org.koin.dsl.module
import org.koin.plugin.module.dsl.single

val sharedModule = module {
    single<HelperMQTT>()
}