package com.exogroup.qnag.data

interface InstanceStore {
    fun getInstances(): List<NagiosInstance>
    fun saveInstances(instances: List<NagiosInstance>)
    fun addInstance(instance: NagiosInstance)
}
