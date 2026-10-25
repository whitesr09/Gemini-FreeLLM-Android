package com.nshd.geminifreellm.ui

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager

/**
 * Switches the launcher icon between the shipped icon and the alternate icons.
 *
 * Android exposes one launcher entry per enabled component, so each alternate icon is an
 * activity-alias of MainActivity in the manifest (LauncherGlow, LauncherBlue), disabled by
 * default. Enabling exactly one of MainActivity / LauncherGlow / LauncherBlue selects the
 * icon the launcher shows. Component state survives reboots and app updates, so nothing
 * needs re-applying on start; the Settings UI reads this same state back.
 */
object LauncherIcon {
    enum class Choice { CURRENT, GOLD, BLUE }

    private const val MAIN_SUFFIX = ".MainActivity"
    private const val GOLD_SUFFIX = ".LauncherGlow"
    private const val BLUE_SUFFIX = ".LauncherBlue"

    /** Pure mapping from PackageManager states to the selected icon; unit-tested directly. */
    internal fun choiceFor(goldState: Int, blueState: Int): Choice = when {
        goldState == PackageManager.COMPONENT_ENABLED_STATE_ENABLED -> Choice.GOLD
        blueState == PackageManager.COMPONENT_ENABLED_STATE_ENABLED -> Choice.BLUE
        else -> Choice.CURRENT
    }

    fun currentChoice(context: Context): Choice {
        val pm = context.packageManager
        val pkg = context.packageName
        return try {
            choiceFor(
                pm.getComponentEnabledSetting(ComponentName(pkg, pkg + GOLD_SUFFIX)),
                pm.getComponentEnabledSetting(ComponentName(pkg, pkg + BLUE_SUFFIX))
            )
        } catch (e: IllegalArgumentException) {
            // Alias missing from this build (e.g. a variant without them): never crash Settings.
            Choice.CURRENT
        }
    }

    /** Returns false instead of crashing if the components are unavailable in this build. */
    fun setChoice(context: Context, choice: Choice): Boolean {
        val pm = context.packageManager
        val pkg = context.packageName
        val states = mapOf(
            MAIN_SUFFIX to (choice == Choice.CURRENT),
            GOLD_SUFFIX to (choice == Choice.GOLD),
            BLUE_SUFFIX to (choice == Choice.BLUE)
        )
        return try {
            for ((suffix, enabled) in states) {
                pm.setComponentEnabledSetting(
                    ComponentName(pkg, pkg + suffix),
                    if (enabled) PackageManager.COMPONENT_ENABLED_STATE_ENABLED
                    else PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                    PackageManager.DONT_KILL_APP
                )
            }
            true
        } catch (e: IllegalArgumentException) {
            false
        }
    }
}
