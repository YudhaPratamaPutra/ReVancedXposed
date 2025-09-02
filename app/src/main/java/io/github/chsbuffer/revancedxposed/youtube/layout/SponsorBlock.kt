package io.github.chsbuffer.revancedxposed.youtube.layout

import android.content.Context
import android.graphics.Canvas
import android.graphics.Rect
import android.view.ViewGroup
import app.revanced.extension.shared.Utils
import app.revanced.extension.youtube.sponsorblock.SegmentPlaybackController
import app.revanced.extension.youtube.sponsorblock.ui.CreateSegmentButton
import app.revanced.extension.youtube.sponsorblock.ui.SponsorBlockAboutPreference
import app.revanced.extension.youtube.sponsorblock.ui.SponsorBlockPreferenceGroup
import app.revanced.extension.youtube.sponsorblock.ui.SponsorBlockStatsPreferenceCategory
import app.revanced.extension.youtube.sponsorblock.ui.SponsorBlockViewController
import app.revanced.extension.youtube.sponsorblock.ui.VotingButton
import io.github.chsbuffer.revancedxposed.R
import io.github.chsbuffer.revancedxposed.addModuleAssets
import io.github.chsbuffer.revancedxposed.scopedHook
import io.github.chsbuffer.revancedxposed.setObjectField
import io.github.chsbuffer.revancedxposed.shared.misc.settings.preference.NonInteractivePreference
import io.github.chsbuffer.revancedxposed.shared.misc.settings.preference.PreferenceCategory
import io.github.chsbuffer.revancedxposed.shared.misc.settings.preference.PreferenceScreenPreference
import io.github.chsbuffer.revancedxposed.youtube.YoutubeHook
import io.github.chsbuffer.revancedxposed.youtube.misc.ControlInitializer
import io.github.chsbuffer.revancedxposed.youtube.misc.PlayerControls
import io.github.chsbuffer.revancedxposed.youtube.misc.PlayerTypeHook
import io.github.chsbuffer.revancedxposed.youtube.misc.PreferenceScreen
import io.github.chsbuffer.revancedxposed.youtube.misc.addTopControl
import io.github.chsbuffer.revancedxposed.youtube.misc.initializeTopControl
import io.github.chsbuffer.revancedxposed.youtube.video.VideoIdPatch
import io.github.chsbuffer.revancedxposed.youtube.video.VideoInformationHook
import io.github.chsbuffer.revancedxposed.youtube.video.playerInitHooks
import io.github.chsbuffer.revancedxposed.youtube.video.videoIdHooks
import io.github.chsbuffer.revancedxposed.youtube.video.videoTimeHooks
import org.luckypray.dexkit.wrap.DexMethod

fun YoutubeHook.SponsorBlock() {
    dependsOn(
        ::VideoInformationHook,
        ::VideoIdPatch,
        ::PlayerTypeHook,
        ::PlayerControls,
    )

    PreferenceScreen.SPONSORBLOCK.addPreferences(
        // SB setting is old code with lots of custom preferences and updating behavior.
        // Added as a preference group and not a fragment so the preferences are searchable.
        PreferenceCategory(
            key = "revanced_settings_screen_10_sponsorblock",
            sorting = PreferenceScreenPreference.Sorting.UNSORTED,
            preferences = emptySet(), // Preferences are added by custom class at runtime.
            tag = SponsorBlockPreferenceGroup::class.java
        ), PreferenceCategory(
            key = "revanced_sb_stats",
            sorting = PreferenceScreenPreference.Sorting.UNSORTED,
            preferences = emptySet(), // Preferences are added by custom class at runtime.
            tag = SponsorBlockStatsPreferenceCategory::class.java
        ), PreferenceCategory(
            key = "revanced_sb_about",
            sorting = PreferenceScreenPreference.Sorting.UNSORTED,
            preferences = setOf(
                NonInteractivePreference(
                    key = "revanced_sb_about_api",
                    tag = SponsorBlockAboutPreference::class.java,
                    selectable = true,
                )
            )
        )
    )

    addTopControl(R.layout.revanced_sb_button)

    // Hook the video time methods.
    videoTimeHooks.add { SegmentPlaybackController.setVideoTime(it) }
    videoIdHooks.add { SegmentPlaybackController.setCurrentVideoId(it) }

    getDexClass("SeekbarClass") {
        findMethod {
            matcher {
                addEqString("timed_markers_width")
                returnType = "void"
            }
        }.single().declaredClass!!.also { clazz ->
            getDexField("SponsorBarRect") {
                clazz.findMethod {
                    matcher {
                        addInvoke {
                            name = "invalidate"
                            paramTypes("android.graphics.Rect")
                        }
                    }
                }.single().usingFields.last { it.field.typeName == "android.graphics.Rect" }.field
            }
            getDexMethod("seekbarOnDrawFingerprint") {
                clazz.findMethod {
                    matcher {
                        name = "onDraw"
                    }
                }.single()
            }
        }
    }

    // Seekbar drawing
    val seekbarOnDrawMethod = getDexMethod("seekbarOnDrawFingerprint")
    var rectSetOnce = false
    seekbarOnDrawMethod.hookMethod {
        val sponsorBarRectField = getDexField("SponsorBarRect").toField()
        before { param ->
            // Get left and right of seekbar rectangle.
            rectSetOnce = false
            SegmentPlaybackController.setSponsorBarRect(sponsorBarRectField.get(param.thisObject) as Rect)
        }
    }
    seekbarOnDrawMethod.hookMethod(
        scopedHook(
            // Set the thickness of the segment.
            DexMethod("Landroid/graphics/Rect;->set(IIII)V").toMethod() to {
                after { param ->
                    // Only the first call to Rect.set from onDraw sets the segment thickness.
                    if (rectSetOnce) return@after
                    SegmentPlaybackController.setSponsorBarThickness((param.thisObject as Rect).height())
                    rectSetOnce = true
                }
            },
            // Find the drawCircle call and draw the segment before it.
            DexMethod("Landroid/graphics/RecordingCanvas;->drawCircle(FFFLandroid/graphics/Paint;)V").toMethod() to {
                before { param ->
                    SegmentPlaybackController.drawSponsorTimeBars(
                        param.thisObject as Canvas, param.args[1] as Float
                    )
                }
            },
        )
    )

    // Change visibility of the buttons.
    initializeTopControl(
        ControlInitializer(
            R.id.revanced_sb_create_segment_button,
            CreateSegmentButton::initialize,
            CreateSegmentButton::setVisibility,
            CreateSegmentButton::setVisibilityImmediate,
            CreateSegmentButton::setVisibilityNegatedImmediate
        )
    )
    initializeTopControl(
        ControlInitializer(
            R.id.revanced_sb_voting_button,
            VotingButton::initialize,
            VotingButton::setVisibility,
            VotingButton::setVisibilityImmediate,
            VotingButton::setVisibilityNegatedImmediate
        )
    )

    // TODO Append the new time to the player layout.

    // Initialize the player controller.
    playerInitHooks.add { SegmentPlaybackController.initialize(it) }

    // Initialize the SponsorBlock view.
    val inset_overlay_view_layout = Utils.getResourceIdentifier("inset_overlay_view_layout", "id")
    val controls_overlay_layout =
        Utils.getResourceIdentifier("size_adjustable_youtube_controls_overlay", "layout")
    getDexMethod("controlsOverlayFingerprint") {
        findMethod {
            matcher {
                addUsingNumber(inset_overlay_view_layout)
                paramCount = 0
                returnType = "void"
            }
        }.single()
    }.hookMethod(scopedHook(DexMethod("Landroid/view/LayoutInflater;->inflate(ILandroid/view/ViewGroup;)Landroid/view/View;").toMember()) {
        after { param ->
            if (param.args[0] != controls_overlay_layout) return@after
            val layout = param.result as ViewGroup
            layout.context.addModuleAssets()
            Utils.getContext().addModuleAssets()
            val overlay_view = layout.findViewById<ViewGroup>(inset_overlay_view_layout)
            SponsorBlockViewController.initialize(overlay_view)
        }
    })

    fun injectClassLoader(self: ClassLoader, host: ClassLoader) {
        val bootClassLoader = Context::class.java.classLoader!!
        host.setObjectField("parent", object : ClassLoader(bootClassLoader) {
            override fun findClass(name: String): Class<*> {
                try {
                    return bootClassLoader.loadClass(name)
                } catch (ignored: ClassNotFoundException) {
                }

                try {
                    if (name.startsWith("app.revanced")) return self.loadClass(name)
                } catch (ignored: ClassNotFoundException) {
                }

                throw ClassNotFoundException(name)
            }
        })
    }

    injectClassLoader(this::class.java.classLoader!!, classLoader)
}
