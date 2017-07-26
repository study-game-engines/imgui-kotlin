package imgui.imgui

import gli.has
import gli.hasnt
import glm_.f
import glm_.glm
import glm_.i
import glm_.vec2.Vec2
import imgui.*
import imgui.ImGui.buttonBehavior
import imgui.ImGui.clearActiveId
import imgui.ImGui.closeButton
import imgui.ImGui.columns
import imgui.ImGui.currentWindow
import imgui.ImGui.currentWindowRead
import imgui.ImGui.findWindowByName
import imgui.ImGui.focusWindow
import imgui.ImGui.getColorU32
import imgui.ImGui.getColumnOffset
import imgui.ImGui.itemAdd
import imgui.ImGui.itemSize
import imgui.ImGui.popClipRect
import imgui.ImGui.pushClipRect
import imgui.ImGui.renderCollapseTriangle
import imgui.ImGui.renderFrame
import imgui.ImGui.renderTextClipped
import imgui.internal.*
import imgui.Context as g


interface imgui_window {

    /*  Push a new ImGui window to add widgets to:
        - A default window called "Debug" is automatically stacked at the beginning of every frame so you can use
            widgets without explicitly calling a Begin/End pair.
        - Begin/End can be called multiple times during the frame with the same window name to append content.
        - 'size_on_first_use' for a regular window denote the initial size for first-time creation (no saved data) and
            isn't that useful. Use SetNextWindowSize() prior to calling Begin() for more flexible window manipulation.
        - The window name is used as a unique identifier to preserve window information across frames (and save
            rudimentary information to the .ini file).
            You can use the "##" or "###" markers to use the same label with different id, or same id with different
            label. See documentation at the top of this file.
        - Return false when window is collapsed, so you can early out in your code. You always need to call ImGui::End()
            even if false is returned.
        - Passing 'bool* p_open' displays a Close button on the upper-right corner of the window, the pointed value will
            be set to false when the button is pressed.
        - Passing non-zero 'size' is roughly equivalent to calling SetNextWindowSize(size, ImGuiSetCond_FirstUseEver)
            prior to calling Begin().   */
    fun begin(name: String, pOpen: BooleanArray? = null, flags: Int = 0) = begin(name, pOpen, Vec2(), -1.0f, flags)

    /** OBSOLETE. this is the older/longer API. the extra parameters aren't very relevant. call SetNextWindowSize() instead
    if you want to set a window size. For regular windows, 'size_on_first_use' only applies to the first time EVER the
    window is created and probably not what you want! might obsolete this API eventually.   */
    fun begin(name: String, pOpen: BooleanArray?, sizeOnFirstUse: Vec2, bgAlpha: Float = -1.0f, flags: Int = 0): Boolean {

        assert(name.isNotEmpty())   // Window name required
        assert(g.initialized)       // Forgot to call ImGui::NewFrame()
        // Called ImGui::Render() or ImGui::EndFrame() and haven't called ImGui::NewFrame() again yet
        assert(g.frameCountEnded != g.frameCount)

        var flags = flags
        if (flags has WindowFlags.NoInputs)
            flags = flags or WindowFlags.NoMove or WindowFlags.NoResize

        // Find or create
        var windowIsNew = false
        var window = findWindowByName(name)
        if (window == null) {
            window = createNewWindow(name, sizeOnFirstUse, flags)
            windowIsNew = true
        }

        val currentFrame = frameCount
        val firstBeginOfTheFrame = window.lastFrameActive != currentFrame
        if (firstBeginOfTheFrame)
            window.flags = flags
        else
            flags = window.flags

        // Add to stack
        val parentWindow = g.currentWindowStack.lastOrNull()
        g.currentWindowStack.add(window)
        window.setCurrent()
        checkStacksSize(window, true)
        assert(parentWindow != null || flags hasnt WindowFlags.ChildWindow)
        // Not using !WasActive because the implicit "Debug" window would always toggle off->on
        var windowWasActive = window.lastFrameActive == currentFrame - 1
        if (flags has WindowFlags.Popup) {
            val popupRef = g.openPopupStack[g.currentPopupStack.size]
            windowWasActive = windowWasActive && window.popupId == popupRef.popupId
            windowWasActive = windowWasActive && window === popupRef.window
            popupRef.window = window
            g.currentPopupStack.push(popupRef)
            window.popupId = popupRef.popupId
        }

        val windowAppearingAfterBeingHidden = window.hiddenFrames == 1

        // Process SetNextWindow***() calls
        var windowPosSetByApi = false
        var windowSizeSetByApi = false
        if (g.setNextWindowPosCond != SetCond.Null) {
            val backupCursorPos = Vec2(window.dc.cursorPos)   // FIXME: not sure of the exact reason of this saving/restore anymore :( need to look into that.
            if (!windowWasActive || windowAppearingAfterBeingHidden)
                window.setWindowPosAllowFlags = window.setWindowPosAllowFlags or SetCond.Appearing
            windowPosSetByApi = window.setWindowPosAllowFlags has g.setNextWindowPosCond
            if (windowPosSetByApi && (g.setNextWindowPosVal - Vec2(-Float.MAX_VALUE)).lengthSqr() < 0.001f) {
                window.setWindowPosCenterWanted = true                            // May be processed on the next frame if this is our first frame and we are measuring size
                window.setWindowPosAllowFlags =
                        window.setWindowPosAllowFlags and (SetCond.Once or SetCond.FirstUseEver or SetCond.Appearing).inv()
            } else
                window.setPos(g.setNextWindowPosVal, g.setNextWindowPosCond)

            window.dc.cursorPos put backupCursorPos
            g.setNextWindowPosCond = SetCond.Null
        }
        if (g.setNextWindowSizeCond.i != 0) {
            if (!windowWasActive || windowAppearingAfterBeingHidden)
                window.setWindowSizeAllowFlags = window.setWindowSizeAllowFlags or SetCond.Appearing
            windowSizeSetByApi = window.setWindowSizeAllowFlags has g.setNextWindowSizeCond
            window.setSize(g.setNextWindowSizeVal, g.setNextWindowSizeCond)
            g.setNextWindowSizeCond = SetCond.Null
        }
        if (g.setNextWindowContentSizeCond != SetCond.Null) {
            window.sizeContentsExplicit put g.setNextWindowContentSizeVal
            g.setNextWindowContentSizeCond = SetCond.Null
        } else if (firstBeginOfTheFrame)
            window.sizeContentsExplicit put 0f
        if (g.setNextWindowCollapsedCond != SetCond.Null) {
            if (!windowWasActive || windowAppearingAfterBeingHidden)
                window.setWindowCollapsedAllowFlags = window.setWindowCollapsedAllowFlags or SetCond.Appearing
            window.setCollapsed(g.setNextWindowCollapsedVal, g.setNextWindowCollapsedCond)
            g.setNextWindowCollapsedCond = SetCond.Null
        }
        if (g.setNextWindowFocus) {
            setWindowFocus()
            g.setNextWindowFocus = false
        }

        // Update known root window (if we are a child window, otherwise window == window->RootWindow)
        var rootIdx = g.currentWindowStack.lastIndex
        while (rootIdx > 0) {
            if (g.currentWindowStack[rootIdx].flags hasnt WindowFlags.ChildWindow)
                break
            rootIdx--
        }
        var rootNonPopupIdx = rootIdx
        while (rootNonPopupIdx > 0) {
            if (g.currentWindowStack[rootNonPopupIdx].flags hasnt (WindowFlags.ChildWindow or WindowFlags.Popup))
                break
            rootNonPopupIdx--
        }
        window.parentWindow = parentWindow
        window.rootWindow = g.currentWindowStack[rootIdx]
        window.rootNonPopupWindow = g.currentWindowStack[rootNonPopupIdx]   // This is merely for displaying the TitleBgActive color.

        // When reusing window again multiple times a frame, just append content (don't need to setup again)
        if (firstBeginOfTheFrame) {

            window.active = true
            window.indexWithinParent = 0
            window.beginCount = 0
            window.clipRect.put(-Float.MAX_VALUE, -Float.MAX_VALUE, +Float.MAX_VALUE, +Float.MAX_VALUE)
            window.lastFrameActive = currentFrame
            val tmp = window.idStack[0] // resize 1
            window.idStack.clear()
            window.idStack.add(tmp)

            // Clear draw list, setup texture, outer clipping rectangle
            window.drawList.clear()
            window.drawList.pushTextureId(g.font.containerAtlas.texId)
            val fullscreenRect = getVisibleRect()
            if (flags has WindowFlags.ChildWindow && flags hasnt (WindowFlags.ComboBox or WindowFlags.Popup))
                pushClipRect(parentWindow!!.clipRect.min, parentWindow.clipRect.max, true)
            else
                pushClipRect(fullscreenRect.min, fullscreenRect.max, true)

            if (!windowWasActive) {
                // Popup first latch mouse position, will position itself when it appears next frame
                window.autoPosLastDirection = -1
                if (flags has WindowFlags.Popup && !windowPosSetByApi)
                    window.posF put IO.mousePos
            }

            /* Collapse window by double-clicking on title bar
            At this point we don't have a clipping rectangle setup yet, so we can use the title bar area for hit
            detection and drawing   */
            if (flags hasnt WindowFlags.NoTitleBar && flags hasnt WindowFlags.NoCollapse) {
                val titleBarRect = window.titleBarRect()
                if (g.hoveredWindow === window && isMouseHoveringRect(titleBarRect) && IO.mouseDoubleClicked[0]) {
                    window.collapsed = !window.collapsed
                    if (flags hasnt WindowFlags.NoSavedSettings)
                        markIniSettingsDirty()
                    focusWindow(window)
                }
            } else window.collapsed = false

            // SIZE

            // Save contents size from last frame for auto-fitting (unless explicitly specified)
            window.sizeContents.x = (
                    if (window.sizeContentsExplicit.x != 0.0f) window.sizeContentsExplicit.x
                    else (
                            if (windowIsNew) 0.0f
                            else window.dc.cursorMaxPos.x - window.pos.x) + window.scroll.x).i.f
            window.sizeContents.y = (
                    if (window.sizeContentsExplicit.y != 0.0f) window.sizeContentsExplicit.y
                    else (
                            if (windowIsNew) 0.0f
                            else window.dc.cursorMaxPos.y - window.pos.y) + window.scroll.y).i.f

            // Hide popup/tooltip window when first appearing while we measure size (because we recycle them)
            if (window.hiddenFrames > 0)
                window.hiddenFrames--
            if (flags has (WindowFlags.Popup or WindowFlags.Tooltip) && !windowWasActive) {
                window.hiddenFrames = 1
                if (flags has WindowFlags.AlwaysAutoResize) {
                    if (!windowSizeSetByApi) {
                        window.sizeFull put 0f
                        window.size put 0f
                    }
                    window.sizeContents put 0f
                }
            }

            // Lock window padding so that altering the ShowBorders flag for children doesn't have side-effects.
            if (flags has WindowFlags.ChildWindow &&
                    flags hasnt (WindowFlags.AlwaysUseWindowPadding or WindowFlags.ShowBorders or WindowFlags.ComboBox or WindowFlags.Popup))
                window.windowPadding put 0f
            else
                window.windowPadding put Style.windowPadding

            // Calculate auto-fit size
            val sizeAutoFit: Vec2
            if (flags has WindowFlags.Tooltip)
            // Tooltip always resize. We keep the spacing symmetric on both axises for aesthetic purpose.
                sizeAutoFit = window.sizeContents + window.windowPadding - Vec2(0f, Style.itemSpacing.y)
            else {
                sizeAutoFit = window.sizeContents + window.windowPadding
                sizeAutoFit.x = glm.clamp(sizeAutoFit.x, Style.windowMinSize.x.f,
                        glm.max(Style.windowMinSize.x.f, IO.displaySize.x - Style.displaySafeAreaPadding.x))
                sizeAutoFit.y = glm.clamp(sizeAutoFit.y, Style.windowMinSize.y.f,
                        glm.max(Style.windowMinSize.y.f, IO.displaySize.y - Style.displaySafeAreaPadding.y))

                // Handling case of auto fit window not fitting in screen on one axis, we are growing auto fit size on the other axis to compensate for expected scrollbar. FIXME: Might turn bigger than DisplaySize-WindowPadding.
                if (sizeAutoFit.x < window.sizeContents.x && flags hasnt WindowFlags.NoScrollbar && flags has WindowFlags.HorizontalScrollbar)
                    sizeAutoFit.y += Style.scrollbarSize
                if (sizeAutoFit.y < window.sizeContents.y && flags hasnt WindowFlags.NoScrollbar)
                    sizeAutoFit.x += Style.scrollbarSize
                sizeAutoFit.y = glm.max(sizeAutoFit.y - Style.itemSpacing.y, 0f)
            }

            // Handle automatic resize
            if (window.collapsed) {
                /*  We still process initial auto-fit on collapsed windows to get a window width, but otherwise we don't
                honor ImGuiWindowFlags_AlwaysAutoResize when collapsed. */
                if (window.autoFitFrames.x > 0)
                    window.sizeFull.x = if (window.autoFitOnlyGrows) glm.max(window.sizeFull.x, sizeAutoFit.x) else sizeAutoFit.x
                if (window.autoFitFrames.y > 0)
                    window.sizeFull.y = if (window.autoFitOnlyGrows) glm.max(window.sizeFull.y, sizeAutoFit.y) else sizeAutoFit.y
            } else {
                if (flags has WindowFlags.AlwaysAutoResize && !windowSizeSetByApi)
                    window.sizeFull put sizeAutoFit
                else if ((window.autoFitFrames.x > 0 || window.autoFitFrames.y > 0) && !windowSizeSetByApi) {
                    // Auto-fit only grows during the first few frames
                    if (window.autoFitFrames.x > 0)
                        window.sizeFull.x = if (window.autoFitOnlyGrows) glm.max(window.sizeFull.x, sizeAutoFit.x) else sizeAutoFit.x
                    if (window.autoFitFrames.y > 0)
                        window.sizeFull.y = if (window.autoFitOnlyGrows) glm.max(window.sizeFull.y, sizeAutoFit.y) else sizeAutoFit.y
                    if (flags hasnt WindowFlags.NoSavedSettings)
                        markIniSettingsDirty()
                }
            }

            // Apply minimum/maximum window size constraints and final size
            window.applySizeFullWithConstraint(window.sizeFull)
            window.size put if (window.collapsed) window.titleBarRect().size else window.sizeFull

            // POSITION

            // Position child window
            if (flags has WindowFlags.ChildWindow) {
                window.indexWithinParent = parentWindow!!.dc.childWindows.size
                parentWindow.dc.childWindows.add(window)
            }
            if (flags has WindowFlags.ChildWindow && flags hasnt WindowFlags.Popup) {
                window.posF put parentWindow!!.dc.cursorPos
                window.pos put window.posF
                /*  NB: argument name 'size_on_first_use' misleading here, it's really just 'size' as provided by user
                passed via BeginChild()->Begin().   */
                window.sizeFull put sizeOnFirstUse
                window.size put window.sizeFull
            }

            var windowPosCenter = false
            windowPosCenter = windowPosCenter || (window.setWindowPosCenterWanted && window.hiddenFrames == 0)
            windowPosCenter = windowPosCenter || (flags has WindowFlags.Modal && !windowPosSetByApi && windowAppearingAfterBeingHidden)
            if (windowPosCenter)
            // Center (any sort of window)
                window.setPos(glm.max(Style.displaySafeAreaPadding, fullscreenRect.center - window.sizeFull * 0.5f), SetCond.Null)
            else if (flags has WindowFlags.ChildMenu) {
                /*  Child menus typically request _any_ position within the parent menu item, and then our
                FindBestPopupWindowPos() function will move the new menu outside the parent bounds.
                This is how we end up with child menus appearing (most-commonly) on the right of the parent menu.   */
                assert(windowPosSetByApi)
                /*  We want some overlap to convey the relative depth of each popup (currently the amount of overlap it is
                hard-coded to style.ItemSpacing.x, may need to introduce another style value).  */
                val horizontalOverlap = Style.itemSpacing.x
                val rectToAvoid =
                        if (parentWindow!!.dc.menuBarAppending)
                            Rect(-Float.MAX_VALUE, parentWindow.pos.y + parentWindow.titleBarHeight(),
                                    Float.MAX_VALUE, parentWindow.pos.y + parentWindow.titleBarHeight() + parentWindow.menuBarHeight())
                        else
                            Rect(parentWindow.pos.x + horizontalOverlap, -Float.MAX_VALUE,
                                    parentWindow.pos.x + parentWindow.size.x - horizontalOverlap - parentWindow.scrollbarSizes.x, Float.MAX_VALUE)
                window.posF put findBestPopupWindowPos(window.posF, window, rectToAvoid)
            } else if (flags has WindowFlags.Popup && !windowPosSetByApi && windowAppearingAfterBeingHidden) {
                val rectToAvoid = Rect(window.posF.x - 1, window.posF.y - 1, window.posF.x + 1, window.posF.y + 1)
                window.posF put findBestPopupWindowPos(window.posF, window, rectToAvoid)
            }

            // Position tooltip (always follows mouse)
            if (flags has WindowFlags.Tooltip && !windowPosSetByApi) {
                // FIXME: Completely hard-coded. Perhaps center on cursor hit-point instead?
                val rectToAvoid = Rect(IO.mousePos.x - 16, IO.mousePos.y - 8, IO.mousePos.x + 24, IO.mousePos.y + 24)
                window.posF put findBestPopupWindowPos(IO.mousePos, window, rectToAvoid)
                if (window.autoPosLastDirection == -1)
                /*  If there's not enough room, for tooltip we prefer avoiding the cursor at all cost even if it
                means that part of the tooltip won't be visible.    */
                    window.posF = IO.mousePos + 2
            }

            // Clamp position so it stays visible
            if (flags hasnt WindowFlags.ChildWindow && flags hasnt WindowFlags.Tooltip) {
                /*  Ignore zero-sized display explicitly to avoid losing positions if a window manager reports zero-sized
                window when initializing or minimizing. */
                if (!windowPosSetByApi && window.autoFitFrames.x <= 0 && window.autoFitFrames.y <= 0 && IO.displaySize.x > 0f && IO.displaySize.y > 0f) {
                    val padding = glm.max(Style.displayWindowPadding, Style.displaySafeAreaPadding)
                    window.posF put (glm.max(window.posF + window.size, padding) - window.size)
                    window.posF.x = glm.min(window.posF.x, (IO.displaySize.x - padding.x).f)
                    window.posF.y = glm.min(window.posF.y, (IO.displaySize.y - padding.y).f)
                }
            }
            window.pos put window.posF

            // Default item width. Make it proportional to window size if window manually resizes
            window.itemWidthDefault =
                    if (window.size.x > 0f && flags hasnt WindowFlags.Tooltip && flags hasnt WindowFlags.AlwaysAutoResize)
                        (window.size.x * 0.65f).i.f
                    else (g.fontSize * 16f).i.f

            // Prepare for focus requests
            window.focusIdxAllRequestCurrent =
                    if (window.focusIdxAllRequestNext == Int.MAX_VALUE || window.focusIdxAllCounter == -1)
                        Int.MAX_VALUE
                    else (window.focusIdxAllRequestNext + (window.focusIdxAllCounter + 1)) % (window.focusIdxAllCounter + 1)
            window.focusIdxTabRequestCurrent =
                    if (window.focusIdxTabRequestNext == Int.MAX_VALUE || window.focusIdxTabCounter == -1)
                        Int.MAX_VALUE
                    else (window.focusIdxTabRequestNext + (window.focusIdxTabCounter + 1)) % (window.focusIdxTabCounter + 1)
            window.focusIdxTabCounter = -1
            window.focusIdxAllCounter = -1
            window.focusIdxTabRequestNext = Int.MAX_VALUE
            window.focusIdxAllRequestNext = Int.MAX_VALUE

            // Apply scrolling
            if (window.scrollTarget.x < Float.MAX_VALUE) {
                window.scroll.x = window.scrollTarget.x
                window.scrollTarget.x = Float.MAX_VALUE
            }
            if (window.scrollTarget.y < Float.MAX_VALUE) {
                val centerRatio = window.scrollTargetCenterRatio.y
                window.scroll.y = window.scrollTarget.y - ((1f - centerRatio) * (window.titleBarHeight() + window.menuBarHeight())) -
                        (centerRatio * window.sizeFull.y)
                window.scrollTarget.y = Float.MAX_VALUE
            }
            window.scroll = glm.max(window.scroll, 0f)
            if (!window.collapsed && !window.skipItems)
                window.scroll = glm.min(window.scroll, glm.max(window.sizeContents - window.sizeFull + window.scrollbarSizes, 0f))

            // Modal window darkens what is behind them
            if (flags has WindowFlags.Modal && window === getFrontMostModalRootWindow())
                window.drawList.addRectFilled(fullscreenRect.min, fullscreenRect.max,
                        getColorU32(Col.ModalWindowDarkening, g.modalWindowDarkeningRatio))

            // Draw window + handle manual resize
            val titleBarRect = window.titleBarRect()
            val windowRounding = if (flags has WindowFlags.ChildWindow) Style.childWindowRounding else Style.windowRounding
            if (window.collapsed)
            // Draw title bar only
                renderFrame(titleBarRect.tl, titleBarRect.br, getColorU32(Col.TitleBgCollapsed), true, windowRounding)
            else {
                var resizeCol = 0
                val resizeCornerSize = glm.max(g.fontSize * 1.35f, windowRounding + 1.0f + g.fontSize * 0.2f)
                if (flags hasnt WindowFlags.AlwaysAutoResize && window.autoFitFrames.x <= 0 && window.autoFitFrames.y <= 0 &&
                        flags hasnt WindowFlags.NoResize) {
                    // Manual resize
                    val br = window.rect().br
                    val resizeRect = Rect(br - resizeCornerSize * 0.75f, br)
                    val resizeId = window.getId("#RESIZE")
                    val (_, hovered, held) = buttonBehavior(resizeRect, resizeId, ButtonFlags.FlattenChilds)
                    resizeCol = getColorU32(if (held) Col.ResizeGripActive else if (hovered) Col.ResizeGripHovered else Col.ResizeGrip)

                    if (hovered || held)
                        g.mouseCursor = MouseCursor.ResizeNWSE

                    if (g.hoveredWindow === window && held && IO.mouseDoubleClicked[0]) {
                        // Manual auto-fit when double-clicking
                        window.applySizeFullWithConstraint(sizeAutoFit)
                        if (flags hasnt WindowFlags.NoSavedSettings)
                            markIniSettingsDirty()
                        clearActiveId()
                    } else if (held) {
                        // We don't use an incremental MouseDelta but rather compute an absolute target size based on mouse position
                        window.applySizeFullWithConstraint((IO.mousePos - g.activeIdClickOffset + resizeRect.size) - window.pos)
                        if (flags hasnt WindowFlags.NoSavedSettings)
                            markIniSettingsDirty()
                    }

                    window.size put window.sizeFull
                    titleBarRect put window.titleBarRect()
                }

                // Scrollbars
                window.scrollbar.y = flags has WindowFlags.AlwaysVerticalScrollbar ||
                        ((window.sizeContents.y > window.size.y + Style.itemSpacing.y) && flags hasnt WindowFlags.NoScrollbar)
                window.scrollbar.x = flags has WindowFlags.AlwaysHorizontalScrollbar ||
                        ((window.sizeContents.x >
                                window.size.x - (if (window.scrollbar.y) Style.scrollbarSize else 0f) - window.windowPadding.x)
                                && flags hasnt WindowFlags.NoScrollbar && flags has WindowFlags.HorizontalScrollbar)
                window.scrollbarSizes.x = if (window.scrollbar.y) Style.scrollbarSize else 0f
                window.scrollbarSizes.y = if (window.scrollbar.x) Style.scrollbarSize else 0f
                window.borderSize = if (flags has WindowFlags.ShowBorders) 1f else 0f

                // Window background, Default Alpha
                val bgColorIdx = when {
                    flags has WindowFlags.ComboBox -> Col.ComboBg
                    flags has WindowFlags.Tooltip || flags has WindowFlags.Popup -> Col.PopupBg
                    flags has WindowFlags.ChildWindow -> Col.ChildWindowBg
                    else -> Col.WindowBg
                }
                val bgColor = Style.colors[bgColorIdx]
                if (bgAlpha >= 0f)
                    bgColor.w = bgAlpha
                bgColor.w *= Style.alpha
                if (bgColor.w > 0f)
                    window.drawList.addRectFilled(Vec2(0, window.titleBarHeight()) + window.pos, window.size + window.pos,
                            colorConvertFloat4ToU32(bgColor), windowRounding,
                            if (flags has WindowFlags.NoTitleBar) Corner.All.i else Corner.BottomLeft or Corner.BottomRight)

                // Title bar
                if (flags hasnt WindowFlags.NoTitleBar)
                    window.drawList.addRectFilled(titleBarRect.tl, titleBarRect.br,
                            getColorU32(
                                    if (g.focusedWindow != null && window.rootNonPopupWindow == g.focusedWindow!!.rootNonPopupWindow)
                                        Col.TitleBgActive
                                    else Col.TitleBg),
                            windowRounding, Corner.TopLeft or Corner.TopRight)

                // Menu bar
                if (flags has WindowFlags.MenuBar) {
                    val menuBarRect = window.menuBarRect()
                    if (flags has WindowFlags.ShowBorders)
                        window.drawList.addLine(menuBarRect.bl, menuBarRect.br, getColorU32(Col.Border))
                    window.drawList.addRectFilled(menuBarRect.tl, menuBarRect.br, getColorU32(Col.MenuBarBg),
                            if (flags has WindowFlags.NoTitleBar) windowRounding else 0f, Corner.TopLeft or Corner.TopRight)
                }

                // Scrollbars
                if (window.scrollbar.x)
                    scrollbar(window, true)
                if (window.scrollbar.y)
                    scrollbar(window, false)

                /*  Render resize grip
                (after the input handling so we don't have a frame of latency)  */
                if (flags hasnt WindowFlags.NoResize) {
                    val br = window.rect().br
                    window.drawList.pathLineTo(br + Vec2(-resizeCornerSize, -window.borderSize))
                    window.drawList.pathLineTo(br + Vec2(-window.borderSize, -resizeCornerSize))
                    window.drawList.pathArcToFast(Vec2(br.x - windowRounding - window.borderSize, br.y - windowRounding - window.borderSize),
                            windowRounding, 0, 3)
                    window.drawList.pathFillConvex(resizeCol)
                }

                // Borders
                if (flags has WindowFlags.ShowBorders) {
                    window.drawList.addRect(Vec2(1) plus_ window.pos, (window.size + window.pos) plus_ 1, getColorU32(Col.BorderShadow),
                            windowRounding)
                    // TODO check if window.posF is fine instead window.pos
                    window.drawList.addRect(window.posF, window.posF + window.size, getColorU32(Col.Border), windowRounding)
                    if (flags hasnt WindowFlags.NoTitleBar)
                        window.drawList.addLine(titleBarRect.bl + Vec2(1, 0), titleBarRect.br - Vec2(1, 0), getColorU32(Col.Border))
                }
            }

            with(window) {

                // Update ContentsRegionMax. All the variable it depends on are set above in this function.
                contentsRegionRect.min.x = -scroll.x + windowPadding.x
                contentsRegionRect.min.y = -scroll.y + windowPadding.y + titleBarHeight() + menuBarHeight()
                contentsRegionRect.max.x = -scroll.x - windowPadding.x + (
                        if (sizeContentsExplicit.x != 0f) sizeContentsExplicit.x else (size.x - scrollbarSizes.x))
                contentsRegionRect.max.y = -scroll.y - windowPadding.y + (
                        if (sizeContentsExplicit.y != 0f) sizeContentsExplicit.y else (size.y - scrollbarSizes.y))

                // Setup drawing context
                dc.indentX = 0f + windowPadding.x - scroll.x
                dc.groupOffsetX = 0f
                dc.columnsOffsetX = 0.0f
                dc.cursorStartPos = Vec2(dc.indentX + dc.columnsOffsetX, titleBarHeight() + menuBarHeight() + windowPadding.y - scroll.y) + pos
                dc.cursorPos put dc.cursorStartPos
                dc.cursorPosPrevLine put dc.cursorPos
                dc.cursorMaxPos put dc.cursorStartPos
                dc.prevLineHeight = 0f
                dc.currentLineHeight = 0f
                dc.prevLineTextBaseOffset = 0f
                dc.currentLineTextBaseOffset = 0f
                dc.menuBarAppending = false
                dc.menuBarOffsetX = glm.max(windowPadding.x, Style.itemSpacing.x)
                dc.logLinePosY = dc.cursorPos.y - 9999f
                dc.childWindows.clear()
                dc.layoutType = LayoutType.Vertical
                dc.itemWidth = itemWidthDefault
                dc.textWrapPos = -1f // disabled
                dc.allowKeyboardFocus = true
                dc.buttonRepeat = false
                dc.itemWidthStack.clear()
                dc.allowKeyboardFocusStack.clear()
                dc.buttonRepeatStack.clear()
                dc.textWrapPosStack.clear()
                dc.columnsCurrent = 0
                dc.columnsCount = 1
                dc.columnsStartPosY = dc.cursorPos.y
                dc.columnsCellMaxY = dc.columnsStartPosY
                dc.columnsCellMinY = dc.columnsStartPosY
                dc.treeDepth = 0
                dc.stateStorage = stateStorage
                dc.groupStack.clear()
                dc.colorEditMode = ColorEditMode.UserSelect
                menuColumns.update(3, Style.itemSpacing.x, !windowWasActive)

                if (autoFitFrames.x > 0)
                    autoFitFrames.x--
                if (autoFitFrames.y > 0)
                    autoFitFrames.y--
            }

            // New windows appears in front (we need to do that AFTER setting DC.CursorStartPos so our initial navigation reference rectangle can start around there)
            if (!windowWasActive && flags hasnt WindowFlags.NoFocusOnAppearing)
                if (flags hasnt (WindowFlags.ChildWindow or WindowFlags.Tooltip) || flags has WindowFlags.Popup)
                    focusWindow(window)

            // Title bar
            if (flags hasnt WindowFlags.NoTitleBar) {
                if (pOpen != null) {
                    val pad = 2f
                    val rad = (window.titleBarHeight() - pad * 2f) * 0.5f
                    if (closeButton(window.getId("#CLOSE"), window.rect().tr + Vec2(-pad - rad, pad + rad), rad))
                        pOpen[0] = false
                }

                val textSize = calcTextSize(name, hideTextAfterDoubleHash = true)
                if (flags hasnt WindowFlags.NoCollapse)
                    renderCollapseTriangle(Style.framePadding + window.pos, !window.collapsed, 1f)

                val textMin = Vec2(window.pos)
                val textMax = Vec2(window.size.x, Style.framePadding.y * 2 + textSize.y) + window.pos
                val clipRect = Rect()
                // Match the size of CloseWindowButton()
                clipRect.max = Vec2(
                        window.pos.x + window.size.x - (if (pOpen != null) titleBarRect.height - 3 else Style.framePadding.x),
                        textMax.y)
                val padLeft =
                        if (flags hasnt WindowFlags.NoCollapse) Style.framePadding.x + g.fontSize + Style.itemInnerSpacing.x
                        else Style.framePadding.x
                var padRight =
                        if (pOpen != null) Style.framePadding.x + g.fontSize + Style.itemInnerSpacing.x
                        else Style.framePadding.x
                if (Style.windowTitleAlign.x > 0f)
                    padRight = lerp(padRight, padLeft, Style.windowTitleAlign.x)
                textMin.x += padLeft
                textMax.x -= padRight
                clipRect.min = Vec2(textMin.x, window.pos.y)
                renderTextClipped(textMin, textMax, name, name.length, textSize, Style.windowTitleAlign, clipRect)
            }

            // Save clipped aabb so we can access it in constant-time in FindHoveredWindow()
            window.windowRectClipped put window.rect()
            window.windowRectClipped.clip(window.clipRect)

            // Pressing CTRL+C while holding on a window copy its content to the clipboard
            // This works but 1. doesn't handle multiple Begin/End pairs, 2. recursing into another Begin/End pair - so we need to work that out and add better logging scope.
            // Maybe we can support CTRL+C on every element?
            /*
        if (g.ActiveId == move_id)
            if (g.IO.KeyCtrl && IsKeyPressedMap(ImGuiKey_C))
                ImGui::LogToClipboard();
        */
            // TODO return pOpen?
        }

        /*  Inner clipping rectangle
        We set this up after processing the resize grip so that our clip rectangle doesn't lag by a frame
        Note that if our window is collapsed we will end up with a null clipping rectangle which is the correct 
        behavior.   */
        val titleBarRect = window.titleBarRect()
        val borderSize = window.borderSize
        val clipRect = Rect()   // Force round to ensure that e.g. (int)(max.x-min.x) in user's render code produce correct result.
        clipRect.min.x = glm.floor(0.5f + titleBarRect.min.x + glm.max(borderSize, glm.floor(window.windowPadding.x * 0.5f)))
        clipRect.min.y = glm.floor(0.5f + titleBarRect.max.y + window.menuBarHeight() + borderSize)
        clipRect.max.x = glm.floor(0.5f + window.pos.x + window.size.x - window.scrollbarSizes.x -
                glm.max(borderSize, glm.floor(window.windowPadding.x * 0.5f)))
        clipRect.max.y = glm.floor(0.5f + window.pos.y + window.size.y - window.scrollbarSizes.y - borderSize)
        pushClipRect(clipRect.min, clipRect.max, true)

        // Clear 'accessed' flag last thing
        if (firstBeginOfTheFrame)
            window.accessed = false
        window.beginCount++
        g.setNextWindowSizeConstraint = false

        // Child window can be out of sight and have "negative" clip windows.
        // Mark them as collapsed so commands are skipped earlier (we can't manually collapse because they have no title bar).
        if (flags has WindowFlags.ChildWindow) {

            assert(flags has WindowFlags.NoTitleBar)
            window.collapsed = parentWindow?.collapsed ?: false

            if (flags hasnt WindowFlags.AlwaysAutoResize && window.autoFitFrames.x <= 0 && window.autoFitFrames.y <= 0) // TODO vec2 <= i?
                window.collapsed = window.collapsed || (window.windowRectClipped.min.x >= window.windowRectClipped.max.x
                        || window.windowRectClipped.min.y >= window.windowRectClipped.max.y)

            // We also hide the window from rendering because we've already added its border to the command list.
            // (we could perform the check earlier in the function but it is simpler at this point)
            if (window.collapsed)
                window.active = false
        }
        if (Style.alpha <= 0f)
            window.active = false

        // Return false if we don't intend to display anything to allow user to perform an early out optimization
        window.skipItems = (window.collapsed || !window.active) && window.autoFitFrames.x <= 0 && window.autoFitFrames.y <= 0
        return !window.skipItems
    }

    /** finish appending to current window, pop it off the window stack.    */
    fun end() {

        with(g.currentWindow!!) {

            if (dc.columnsCount != 1) // close columns set if any is open
                columns(1, "#CLOSECOLUMNS")
            popClipRect()   // inner window clip rectangle

            // Stop logging
//TODO            if (flags hasnt WindowFlags.ChildWindow)    // FIXME: add more options for scope of logging
//                logFinish()

            // Pop
            // NB: we don't clear 'window->RootWindow'. The pointer is allowed to live until the next call to Begin().
            g.currentWindowStack.pop()
            if (flags has WindowFlags.Popup)
                g.currentPopupStack.pop()
            checkStacksSize(this, false)
            val last = g.currentWindowStack.lastOrNull()
            if (last != null) last.setCurrent()
            else g.currentWindow = null
        }
    }

    fun beginChild(strId: String, size: Vec2 = Vec2(), border: Boolean = false, extraFlags: Int = 0) =
            beginChildEx(strId, currentWindow.getId(strId), size, border, extraFlags)

    /** begin a scrolling region. size==0.0f: use remaining window size, size<0.0f: use remaining window size minus
     *  abs(size). size>0.0f: fixed size. each axis can use a different mode, e.g. ImVec2(0,400).   */
    fun beginChild(id: Int, sizeArg: Vec2 = Vec2(), border: Boolean = false, extraFlags: Int = 0) =
            beginChildEx("", id, sizeArg, border, extraFlags)

    fun beginChildEx(name: String, id: Int, sizeArg: Vec2, border: Boolean, extraFlags: Int): Boolean {

        val window = currentWindow
        var flags = WindowFlags.NoTitleBar or WindowFlags.NoResize or WindowFlags.NoSavedSettings or WindowFlags.ChildWindow

        val contentAvail = contentRegionAvail
        val size = glm.floor(sizeArg)
        if (size.x <= 0f) {
            if (size.x == 0f)
                flags = flags or WindowFlags.ChildWindowAutoFitX
            // Arbitrary minimum zero-ish child size of 4.0f (0.0f causing too much issues)
            size.x = glm.max(contentAvail.x, 4f) - glm.abs(size.x)
        }
        if (size.y <= 0f) {
            if (size.y == 0f)
                flags = flags or WindowFlags.ChildWindowAutoFitY
            size.y = glm.max(contentAvail.y, 4f) - glm.abs(size.y)
        }
        if (border)
            flags = flags or WindowFlags.ShowBorders
        flags = flags or extraFlags

        val title =
                if (name.isNotEmpty())
                    "%s.%s.%08X".format(Style.locale, window.name, name, id)
                else
                    "%s.%08X".format(Style.locale, window.name, id)

        val ret = ImGui.begin(title, null, size, -1f, flags)

        if (window.flags hasnt WindowFlags.ShowBorders)
            ImGui.currentWindow.flags = ImGui.currentWindow.flags and WindowFlags.ShowBorders.i.inv()

        return ret
    }

    fun endChild() {

        var window = currentWindow

        assert(window.flags has WindowFlags.ChildWindow)   // Mismatched BeginChild()/EndChild() callss
        if (window.flags has WindowFlags.ComboBox || window.beginCount > 1)
            ImGui.end()
        else {
            /*  When using auto-filling child window, we don't provide full width/height to ItemSize so that it doesn't
                feed back into automatic size-fitting.             */
            val sz = Vec2(windowSize)
            // Arbitrary minimum zero-ish child size of 4.0f causes less trouble than a 0.0f
            if (window.flags has WindowFlags.ChildWindowAutoFitX)
                sz.x = glm.max(4f, sz.x)
            if (window.flags has WindowFlags.ChildWindowAutoFitY)
                sz.y = glm.max(4f, sz.y)

            ImGui.end()

            window = currentWindow // TODO check if needed
            val bb = Rect(window.dc.cursorPos, window.dc.cursorPos + sz)
            itemSize(sz)
            itemAdd(bb)
        }
    }

    /** current content boundaries (typically window boundaries including scrolling, or current column boundaries), in
     *  windows coordinates
     *  In window space (not screen space!) */
    val contentRegionMax: Vec2 get() = with(currentWindowRead!!) {
        val mx = Vec2(contentsRegionRect.max)
        if (dc.columnsCount != 1)
            mx.x = getColumnOffset(dc.columnsCurrent + 1) - windowPadding.x
        return mx
    }

    /** == GetContentRegionMax() - GetCursorPos()   */
    val contentRegionAvail get() = with(currentWindowRead!!) { contentRegionMax - (dc.cursorPos - pos) }

    val contentRegionAvailWidth get() = contentRegionAvail.x
    /** content boundaries min (roughly (0,0)-Scroll), in window coordinates    */
    val windowContentRegionMin get() = currentWindowRead!!.contentsRegionRect.min
    /** content boundaries max (roughly (0,0)+Size-Scroll) where Size can be override with SetNextWindowContentSize(),
     * in window coordinates    */
    val windowContentRegionMax get() = currentWindowRead!!.contentsRegionRect.max

    val windowContentRegionWidth get() = with(currentWindowRead!!) { contentsRegionRect.max.x - contentsRegionRect.min.x }
    /** get rendering command-list if you want to append your own draw primitives   */
    val windowDrawList get() = currentWindow.drawList
    /** get current window position in screen space (useful if you want to do your own drawing via the DrawList api)    */
    val windowPos get() = g.currentWindow!!.pos

    /** get current window size */
    val windowSize get() = currentWindowRead!!.size

    val windowWidth get() = g.currentWindow!!.size.x

    val windowHeight get() = g.currentWindow!!.size.y

    val isWindowCollapsed get() = g.currentWindow!!.collapsed
    /** per-window font scale. Adjust IO.FontGlobalScale if you want to scale all windows   */
    fun setWindowFontScale(scale: Float) = with(currentWindow) {
        fontWindowScale = scale
        g.fontSize = calcFontSize()
    }

    /** set next window position. call before Begin()   */
    fun setNextWindowPos(pos: Vec2, cond: SetCond = SetCond.Always) {
        g.setNextWindowPosVal put pos
        g.setNextWindowPosCond = cond
    }

    fun setNextWindowPosCenter(cond: SetCond = SetCond.Always) {                      // set next window position to be centered on screen. call before Begin()
        g.setNextWindowPosVal put -Float.MAX_VALUE
        g.setNextWindowPosCond = cond
    }

    /** set next window size. set axis to 0.0f to force an auto-fit on this axis. call before Begin()   */
    fun setNextWindowSize(size: Vec2, cond: SetCond = SetCond.Always) {
        g.setNextWindowSizeVal = size
        g.setNextWindowSizeCond = cond
    }

//IMGUI_API void          SetNextWindowSizeConstraints(const ImVec2& size_min, const ImVec2& size_max, ImGuiSizeConstraintCallback custom_callback = NULL, void* custom_callback_data = NULL); // set next window size limits. use -1,-1 on either X/Y axis to preserve the current size. Use callback to apply non-trivial programmatic constraints.

    /** set next window content size (enforce the range of scrollbars). set axis to 0.0f to leave it automatic. call
     *  before Begin() */
    fun setNextWindowContentSize(size: Vec2) {
        g.setNextWindowContentSizeVal put size
        g.setNextWindowContentSizeCond = SetCond.Always
    }

    /** set next window content width (enforce the range of horizontal scrollbar). call before Begin()  */
    fun setNextWindowContentWidth(width: Float) {
        g.setNextWindowContentSizeVal = Vec2(width,
                if (g.setNextWindowContentSizeCond != SetCond.Null) g.setNextWindowContentSizeVal.y else 0f)
        g.setNextWindowContentSizeCond = SetCond.Always
    }

    /** set next window collapsed state. call before Begin()    */
    fun setNextWindowCollapsed(collapsed: Boolean, cond: SetCond = SetCond.Always) {
        g.setNextWindowCollapsedVal = collapsed
        g.setNextWindowCollapsedCond = cond
    }

    /** set next window to be focused / front-most. call before Begin() */
    fun setNextWindowFocus() {
        g.setNextWindowFocus = true
    }

    /** (not recommended) set current window position - call within Begin()/End(). prefer using SetNextWindowPos(),
     *  as this may incur tearing and side-effects. */
    fun setWindowPos(pos: Vec2, cond: SetCond = SetCond.Null) = currentWindowRead!!.setPos(pos, cond)

    /** (not recommended) set current window size - call within Begin()/End(). set to ImVec2(0,0) to force an auto-fit.
     *  prefer using SetNextWindowSize(), as this may incur tearing and minor side-effects. */
    fun setWindowSize(size: Vec2, cond: SetCond = SetCond.Null) = g.currentWindow!!.setSize(size, cond)

    /** (not recommended) set current window collapsed state. prefer using SetNextWindowCollapsed().    */
    fun setWindowCollapsed(collapsed: Boolean, cond: SetCond = SetCond.Null) = g.currentWindow!!.setCollapsed(collapsed, cond)

    /** (not recommended) set current window to be focused / front-most. prefer using SetNextWindowFocus(). */
    fun setWindowFocus() = focusWindow(g.currentWindow)

    /** set named window position.  */
    fun setWindowPos(name: String, pos: Vec2, cond: SetCond = SetCond.Null) = findWindowByName(name)?.setPos(pos, cond)

    /** set named window size. set axis to 0.0f to force an auto-fit on this axis.  */
    fun setWindowSize(name: String, size: Vec2, cond: SetCond = SetCond.Null) = findWindowByName(name)?.setSize(size, cond)

    /** set named window collapsed state    */
    fun setWindowCollapsed(name: String, collapsed: Boolean, cond: SetCond = SetCond.Null) = findWindowByName(name)?.setCollapsed(collapsed, cond)

    /** set named window to be focused / front-most. use NULL to remove focus.  */
    fun setWindowFocus(name: String) = focusWindow(findWindowByName(name))

    /** scrolling amount [0..GetScrollMaxX()]   */
    var scrollX get() = g.currentWindow!!.scroll.x
        set(value) = with(currentWindow) { scrollTarget.x = scrollX; scrollTargetCenterRatio.x = 0f }

    /** scrolling amount [0..GetScrollMaxY()]   */
    var scrollY get() = g.currentWindow!!.scroll.y
        set(value) = with(currentWindow) {
            // title bar height canceled out when using ScrollTargetRelY
            scrollTarget.y = value + titleBarHeight() + menuBarHeight()
            scrollTargetCenterRatio.y = 0f
        }

    /** get maximum scrolling amount ~~ ContentSize.X - WindowSize.X    */
    val scrollMaxX get() = with(currentWindowRead!!) { sizeContents.x - sizeFull.x - scrollbarSizes.x }

    /** get maximum scrolling amount ~~ ContentSize.Y - WindowSize.Y    */
    val scrollMaxY get() = with(currentWindowRead!!) { sizeContents.y - sizeFull.y - scrollbarSizes.y }

    /** adjust scrolling amount to make current cursor position visible.
     *  centerYRatio = 0.0: top, 0.5: center, 1.0: bottom.    */
    fun setScrollHere(centerYRatio: Float = 0.5f) = with(currentWindow) {
        // Precisely aim above, in the middle or below the last line.
        val targetY = dc.cursorPosPrevLine.y + (dc.prevLineHeight * centerYRatio) + (Style.itemSpacing.y * (centerYRatio - 0.5f) * 2f)
        setScrollFromPosY(targetY - pos.y, centerYRatio)
    }

    /** adjust scrolling amount to make given position valid. use GetCursorPos() or GetCursorStartPos()+offset to get
     *  valid positions.    */
    fun setScrollFromPosY(posY: Float, centerYRatio: Float = 0.5f) = with(currentWindow) {
        /*  We store a target position so centering can occur on the next frame when we are guaranteed to have a known
            window size         */
        assert(centerYRatio in 0f..1f)
        scrollTarget.y = (posY + scroll.y).i.f
        /*  Minor hack to make "scroll to top" take account of WindowPadding,
            else it would scroll to (WindowPadding.y - ItemSpacing.y)         */
        if (centerYRatio <= 0f && scrollTarget.y <= windowPadding.y)
            scrollTarget.y = 0f
        scrollTargetCenterRatio.y = centerYRatio
    }

    /** focus keyboard on the next widget. Use positive 'offset' to access sub components of a multiple component widget.
     *  Use negative 'offset' to access previous widgets.   */
    fun setKeyboardFocusHere(offset: Int = 0) = with(currentWindow) {
        focusIdxAllRequestNext = focusIdxAllCounter + 1 + offset
        focusIdxTabRequestNext = Int.MAX_VALUE
    }
//IMGUI_API void          SetStateStorage(ImGuiStorage* tree);                                // replace tree state storage with our own (if you want to manipulate it yourself, typically clear subsection of it)
//IMGUI_API ImGuiStorage* GetStateStorage();

}