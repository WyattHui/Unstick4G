package com.wyatthui.unstick4g.logger

data class DrivingState(val driveId: Long? = null, val locationEnabled: Boolean = false) {
    val inCar: Boolean
        get() = driveId != null

    val sampleDriveId: Long?
        get() = driveId?.takeIf { locationEnabled }

    val isDriving: Boolean
        get() = sampleDriveId != null
}
