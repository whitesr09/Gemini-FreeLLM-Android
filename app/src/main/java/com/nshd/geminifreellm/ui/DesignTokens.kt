package com.nshd.geminifreellm.ui
object DesignSpace { const val xxs=4; const val xs=8; const val sm=12; const val md=16; const val lg=20; const val xl=24; const val xxl=32 }
object DesignRadius { const val control=12; const val card=18; const val sheet=22; const val pill=24 }
object DesignSize { const val icon=24; const val iconCompact=20; const val touchTarget=48; const val topBar=60 }
object MotionTokens { const val fast=140; const val normal=220; const val emphasized=280 }
data class MotionSettings(val animationsEnabled:Boolean=true)
