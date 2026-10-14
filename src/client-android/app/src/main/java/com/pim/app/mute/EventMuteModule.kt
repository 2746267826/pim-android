package com.pim.app.mute

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * 工单 04（E3）的接口绑定。
 *
 * 与 [com.pim.app.keepalive.KeepAliveModuleBinds] 是**两套独立绑定**：
 * 本模块不改动 keepalive 的任何绑定，只新增日程静音自己的三个能力面
 * （设备静音、设置/会话存储、精确闹钟登记），因此既有测试与行为不受影响。
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class EventMuteModuleBinds {

    @Binds
    @Singleton
    abstract fun bindEventMuteDevice(impl: AndroidEventMuteDevice): EventMuteDevicePort

    @Binds
    @Singleton
    abstract fun bindEventMuteSettingsAccessor(impl: EventMuteSettingsStore): EventMuteSettingsAccessor

    @Binds
    @Singleton
    abstract fun bindMuteSessionStore(impl: EventMuteSettingsStore): MuteSessionStore

    @Binds
    @Singleton
    abstract fun bindEventMuteSchedulePort(impl: EventMuteAlarmScheduler): EventMuteSchedulePort
}
