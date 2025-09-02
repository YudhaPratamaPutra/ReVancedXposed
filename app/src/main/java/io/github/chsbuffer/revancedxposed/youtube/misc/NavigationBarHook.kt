package io.github.chsbuffer.revancedxposed.youtube.misc

import android.annotation.SuppressLint
import android.view.View
import app.revanced.extension.shared.Utils
import app.revanced.extension.youtube.shared.NavigationBar
import app.revanced.extension.youtube.shared.NavigationBar.NavigationButton
import io.github.chsbuffer.revancedxposed.AccessFlags
import io.github.chsbuffer.revancedxposed.accessFlags
import io.github.chsbuffer.revancedxposed.fingerprint
import io.github.chsbuffer.revancedxposed.isStatic
import io.github.chsbuffer.revancedxposed.literal
import io.github.chsbuffer.revancedxposed.returns
import io.github.chsbuffer.revancedxposed.scopedHook
import io.github.chsbuffer.revancedxposed.youtube.YoutubeHook
import java.util.EnumMap


@JvmField
val hookNavigationButtonCreated: MutableList<(NavigationButton, View) -> Unit> = mutableListOf()

@SuppressLint("NonUniqueDexKitData")
fun YoutubeHook.NavigationBarHook() {

    val initializeButtonsFingerprint = getDexMethod("initializeButtonsFingerprint") {
        val pivotBarConstructorFingerprint = fingerprint {
            accessFlags(AccessFlags.PUBLIC, AccessFlags.CONSTRUCTOR)
            strings("com.google.android.apps.youtube.app.endpoint.flags")
        }

        val imageOnlyTabResourceId = Utils.getResourceIdentifier("image_only_tab", "layout")
        pivotBarConstructorFingerprint.declaredClass!!.findMethod {
            matcher {
                accessFlags(AccessFlags.PUBLIC, AccessFlags.FINAL)
                returns("V")
                literal { imageOnlyTabResourceId }
            }
        }.single()
    }

    // Hook the current navigation bar enum value. Note, the 'You' tab does not have an enum value.
    val navigationEnumClass = getDexClass("navigationEnumClass") {
        fingerprint {
            accessFlags(AccessFlags.STATIC, AccessFlags.CONSTRUCTOR)
            strings(
                "PIVOT_HOME",
                "TAB_SHORTS",
                "CREATION_TAB_LARGE",
                "PIVOT_SUBSCRIPTIONS",
                "TAB_ACTIVITY",
                "VIDEO_LIBRARY_WHITE",
                "INCOGNITO_CIRCLE",
            )
        }.declaredClass!!
    }

    val getNavigationEnumMethod = getDexMethod("navigationEnumClass_INVOKE_STATIC") {
        this.getMethodData(initializeButtonsFingerprint.toString())!!.invokes.findMethod {
            matcher {
                declaredClass(navigationEnumClass.className)
                accessFlags(AccessFlags.STATIC)
            }
        }.single()
    }
    initializeButtonsFingerprint.hookMethod(scopedHook(getNavigationEnumMethod.toMethod()) {
        after { param -> NavigationBar.setLastAppNavigationEnum(param.result as Enum<*>) }
    })

    // Hook the creation of navigation tab views.
    val pivotBarButtonsCreateDrawableViewFingerprint =
        getDexMethod("pivotBarButtonsCreateDrawableViewFingerprint") {
            findMethod {
                matcher {
                    accessFlags(AccessFlags.PUBLIC, AccessFlags.FINAL)
                    returns("Landroid/view/View;")
                    declaredClass {
                        descriptor =
                            "Lcom/google/android/libraries/youtube/rendering/ui/pivotbar/PivotBar;"
                    }
                }
            }.single {
                it.paramTypes.firstOrNull()?.descriptor == "Landroid/graphics/drawable/Drawable;"
            }
        }

    initializeButtonsFingerprint.hookMethod(scopedHook(pivotBarButtonsCreateDrawableViewFingerprint.toMethod()) {
        after { param -> NavigationBar.navigationTabLoaded(param.result as View) }
    })

    val pivotBarButtonsCreateResourceViewFingerprint =
        getDexMethod("pivotBarButtonsCreateResourceViewFingerprint") {
            fingerprint {
                accessFlags(AccessFlags.PUBLIC, AccessFlags.FINAL)
                returns("Landroid/view/View;")
                parameters("L", "Z", "I", "L")
                classMatcher {
                    descriptor =
                        "Lcom/google/android/libraries/youtube/rendering/ui/pivotbar/PivotBar;"
                }
            }
        }

    initializeButtonsFingerprint.hookMethod(scopedHook(pivotBarButtonsCreateResourceViewFingerprint.toMethod()) {
        after { param -> NavigationBar.navigationImageResourceTabLoaded(param.result as View) }
    })

    // Fix YT bug of notification tab missing the filled icon.
    val tabActivityCairo =
        navigationEnumClass.toClass().enumConstants?.firstOrNull { (it as? Enum<*>)?.name == "TAB_ACTIVITY_CAIRO" } as? Enum<*>
    if (tabActivityCairo != null) {
        getDexMethods("setEnumMapFingerprint") {
            findMethod {
                matcher {
                    returns("V")
                    literal {
                        Utils.getResourceIdentifier("yt_fill_bell_black_24", "drawable")
                    }
                }
            }.filter { it.isConstructor || it.isStaticInitializer }
        }.forEach { setEnumMapFingerprint ->
            fun processFields(obj: Any?, clazz: Class<*>) {
                clazz.declaredFields.forEach { field ->
                    field.isAccessible = true
                    if (obj == null && !field.isStatic) return@forEach
                    val enumMap = field.get(obj) as? EnumMap<*, *> ?: return@forEach
                    // check is valueType int (resource id)
                    val valueType = enumMap.values.firstOrNull()?.javaClass ?: return@forEach
                    if (valueType != Int::class.javaObjectType) return@forEach
                    if (!enumMap.containsKey(tabActivityCairo))
                        NavigationBar.setCairoNotificationFilledIcon(enumMap, tabActivityCairo)
                }
            }

            if (setEnumMapFingerprint.isStaticInitializer) {
                processFields(null, classLoader.loadClass(setEnumMapFingerprint.declaredClassName))
            } else {
                setEnumMapFingerprint.hookMethod {
                    after { param ->
                        processFields(param.thisObject, param.thisObject.javaClass)
                    }
                }
            }
        }
    }
}