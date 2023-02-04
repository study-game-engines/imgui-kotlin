package imgui.internal.api

import gli_.has
import glm_.b
import glm_.compareTo
import glm_.func.common.max
import glm_.func.common.min
import glm_.glm
import glm_.i
import glm_.vec1.Vec1i
import glm_.vec2.Vec2
import glm_.vec4.Vec4
import imgui.*
import imgui.ImGui.beginChildEx
import imgui.ImGui.beginGroup
import imgui.ImGui.calcItemSize
import imgui.ImGui.calcItemWidth
import imgui.ImGui.calcTextSize
import imgui.ImGui.clearActiveID
import imgui.ImGui.clipboardText
import imgui.ImGui.currentWindow
import imgui.ImGui.dataTypeApplyFromText
import imgui.ImGui.dataTypeClamp
import imgui.ImGui.dummy
import imgui.ImGui.endChild
import imgui.ImGui.endGroup
import imgui.ImGui.focusWindow
import imgui.ImGui.format
import imgui.ImGui.getColorU32
import imgui.ImGui.getScrollbarID
import imgui.ImGui.io
import imgui.ImGui.isPressed
import imgui.ImGui.itemAdd
import imgui.ImGui.itemHoverable
import imgui.ImGui.itemSize
import imgui.ImGui.logRenderedText
import imgui.ImGui.logSetNextTextDecoration
import imgui.ImGui.markItemEdited
import imgui.ImGui.popFont
import imgui.ImGui.popStyleColor
import imgui.ImGui.popStyleVar
import imgui.ImGui.pushFont
import imgui.ImGui.pushStyleColor
import imgui.ImGui.pushStyleVar
import imgui.ImGui.renderFrame
import imgui.ImGui.renderNavHighlight
import imgui.ImGui.renderText
import imgui.ImGui.scrollMaxY
import imgui.ImGui.setActiveID
import imgui.ImGui.setFocusID
import imgui.ImGui.setOwner
import imgui.ImGui.setScrollY
import imgui.ImGui.style
import imgui.api.g
import imgui.classes.InputTextCallbackData
import imgui.internal.*
import imgui.internal.classes.InputTextState
import imgui.internal.classes.InputTextState.K
import imgui.internal.classes.LastItemData
import imgui.internal.classes.Rect
import imgui.internal.sections.*
import imgui.static.*
import imgui.stb.te.clamp
import imgui.stb.te.click
import imgui.stb.te.cut
import imgui.stb.te.drag
import imgui.stb.te.paste
import uno.kotlin.NUL
import kotlin.math.max
import kotlin.math.min
import kotlin.reflect.KMutableProperty0
import imgui.InputTextFlag as Itf
import imgui.WindowFlag as Wf

/** InputText */
internal interface inputText {

    /** InputTextEx
     *  - bufSize account for the zero-terminator, so a buf_size of 6 can hold "Hello" but not "Hello!".
     *    This is so we can easily call InputText() on static arrays using ARRAYSIZE() and to match
     *    Note that in std::string world, capacity() would omit 1 byte used by the zero-terminator.
     *  - When active, hold on a privately held copy of the text (and apply back to 'buf'). So changing 'buf' while the InputText is active has no effect.
     *  - If you want to use ImGui::InputText() with std::string, see misc/cpp/imgui_stl.h
     *  (FIXME: Rather confusing and messy function, among the worse part of our codebase, expecting to rewrite a V2 at some point.. Partly because we are
     *  doing UTF8 > U16 > UTF8 conversions on the go to easily internal interface with stb_textedit. Ideally should stay in UTF-8 all the time. See https://github.com/nothings/stb/issues/188)
     */
    fun inputTextEx(label: String, hint: String?, buf_: ByteArray, sizeArg: Vec2, flags: InputTextFlags,
                    callback: InputTextCallback? = null, callbackUserData: Any? = null): Boolean {

        var buf = buf_

        val window = currentWindow
        if (window.skipItems)
            return false

        assert(buf.isNotEmpty())
        assert(!(flags has Itf.CallbackHistory && flags has Itf._Multiline)) { "Can't use both together (they both use up/down keys)" }
        assert(!(flags has Itf.CallbackCompletion && flags has Itf.AllowTabInput)) { "Can't use both together (they both use tab key)" }

        val RENDER_SELECTION_WHEN_INACTIVE = false
        val isMultiline = flags has Itf._Multiline
        val isReadOnly = flags has Itf.ReadOnly
        val isPassword = flags has Itf.Password
        val isUndoable = flags hasnt Itf.NoUndoRedo
        val isResizable = flags has Itf.CallbackResize
        if (isResizable)
            assert(callback != null) { "Must provide a callback if you set the ImGuiInputTextFlags_CallbackResize flag!" }
        if (flags has Itf.CallbackCharFilter)
            assert(callback != null) { "Must provide a callback if you want a char filter!" }

        if (isMultiline) // Open group before calling GetID() because groups tracks id created within their scope (including the scrollbar)
            beginGroup()
        val id = window.getID(label)
        val labelSize = calcTextSize(label, hideTextAfterDoubleHash = true)
        val h = if (isMultiline) g.fontSize * 8f else labelSize.y
        val frameSize = calcItemSize(sizeArg, calcItemWidth(), h + style.framePadding.y * 2f) // Arbitrary default of 8 lines high for multi-line
        val totalSize = Vec2(frameSize.x + if (labelSize.x > 0f) style.itemInnerSpacing.x + labelSize.x else 0f, frameSize.y)

        val frameBb = Rect(window.dc.cursorPos, window.dc.cursorPos + frameSize)
        val totalBb = Rect(frameBb.min, frameBb.min + totalSize)

        var drawWindow = window
        val innerSize = Vec2(frameSize)
        val bufEnd = Vec1i()
        val itemStatusFlags: ItemStatusFlags
        lateinit var itemDataBackup: LastItemData
        if (isMultiline) {
            val backupPos = Vec2(window.dc.cursorPos)
            itemSize(totalBb, style.framePadding.y)
            if (!itemAdd(totalBb, id, frameBb, ItemFlag.Inputable.i)) {
                itemSize(totalBb, style.framePadding.y)
                endGroup()
                return false
            }
            itemStatusFlags = g.lastItemData.statusFlags
            itemDataBackup = g.lastItemData
            window.dc.cursorPos = backupPos

            // We reproduce the contents of BeginChildFrame() in order to provide 'label' so our window internal data are easier to read/debug.
            // FIXME-NAV: Pressing NavActivate will trigger general child activation right before triggering our own below. Harmless but bizarre.
            pushStyleColor(Col.ChildBg, style.colors[Col.FrameBg])
            pushStyleVar(StyleVar.ChildRounding, style.frameRounding)
            pushStyleVar(StyleVar.ChildBorderSize, style.frameBorderSize)
            pushStyleVar(StyleVar.WindowPadding, Vec2()) // Ensure no clip rect so mouse hover can reach FramePadding edges
            val childVisible = beginChildEx(label, id, frameBb.size, true, Wf.NoMove.i)
            popStyleVar(3)
            popStyleColor()
            if (!childVisible) {
                endChild()
                endGroup()
                return false
            }
            drawWindow = g.currentWindow!!  // Child window
            // This is to ensure that EndChild() will display a navigation highlight so we can "enter" into it.
            drawWindow.dc.navLayersActiveMaskNext = drawWindow.dc.navLayersActiveMaskNext or (1 shl drawWindow.dc.navLayerCurrent)
            drawWindow.dc.cursorPos plusAssign style.framePadding
            innerSize.x -= drawWindow.scrollbarSizes.x
        } else {
            // Support for internal ImGuiInputTextFlags_MergedItem flag, which could be redesigned as an ItemFlags if needed (with test performed in ItemAdd)
            itemSize(totalBb, style.framePadding.y)
            if (flags hasnt Itf._MergedItem)
                if (!itemAdd(totalBb, id, frameBb, ItemFlag.Inputable.i))
                    return false
            itemStatusFlags = g.lastItemData.statusFlags
        }
        val hovered = itemHoverable(frameBb, id)
        if (hovered) g.mouseCursor = MouseCursor.TextInput

        // We are only allowed to access the state if we are already the active widget.
        var state = getInputTextState(id)

        val inputRequestedByTabbing = itemStatusFlags has ItemStatusFlag.FocusedByTabbing
        val inputRequestedByNav = g.activeId != id && (g.navActivateInputId == id || (g.navActivateId == id && g.navInputSource == InputSource.Keyboard))

        val userClicked = hovered && io.mouseClicked[0]
        val userScrollFinish = isMultiline && state != null && g.activeId == 0 && g.activeIdPreviousFrame == drawWindow getScrollbarID Axis.Y
        val userScrollActive = isMultiline && state != null && g.activeId == drawWindow getScrollbarID Axis.Y
        var clearActiveId = false
        var selectAll = false

        var scrollY = if (isMultiline) drawWindow.scroll.y else Float.MAX_VALUE

        val initChangedSpecs = state != null && state.stb.singleLine != !isMultiline
        val initMakeActive = userClicked || userScrollFinish || inputRequestedByNav || inputRequestedByTabbing
        val initState = initMakeActive || userScrollActive
        if ((initState && g.activeId != id) || initChangedSpecs) {
            // Access state even if we don't own it yet.
            state = g.inputTextState
            state.cursorAnimReset()

            // Take a copy of the initial buffer value (both in original UTF-8 format and converted to wchar)
            // From the moment we focused we are ignoring the content of 'buf' (unless we are in read-only mode)
            val bufLen = buf.strlen()
            if (state.initialTextA.size < bufLen)
            // [JVM] we don't need the +1 for the termination char
                state.initialTextA = ByteArray(bufLen)   // UTF-8. we use +1 to make sure that .Data is always pointing to at least an empty string.
            else if (state.initialTextA.size > bufLen)
                state.initialTextA[bufLen] = 0
            System.arraycopy(buf, 0, state.initialTextA, 0, bufLen)

            // Preserve cursor position and undo/redo stack if we come back to same widget
            // FIXME: Since we reworked this on 2022/06, may want to differenciate recycle_cursor vs recycle_undostate?
            var recycleState = state.id == id && !initChangedSpecs
            if (recycleState && (state.curLenA != bufLen || (state.textAIsValid && state.textA.strncmp(buf, bufLen) != 0)))
                recycleState = false

            // Start edition
            if (state.textW.size < buf.size)
            // [JVM] we don't need the +1 for the termination char
                state.textW = CharArray(buf.size)   // wchar count <= UTF-8 count. we use +1 to make sure that .Data is always pointing to at least an empty string.
            else if (state.textW.size > buf.size)
                state.textW[buf.size] = NUL
            state.textAIsValid = false // TextA is not valid yet (we will display buf until then)
            state.curLenW = textStrFromUtf8(state.textW, buf, textRemaining = bufEnd)
            state.curLenA = bufEnd[0] // We can't get the result from ImStrncpy() above because it is not UTF-8 aware. Here we'll cut off malformed UTF-8.

            if (recycleState)
            // Recycle existing cursor/selection/undo stack but clamp position
            // Note a single mouse click will override the cursor/position immediately by calling stb_textedit_click handler.
                state.cursorClamp()
            else {
                state.scrollX = 0f
                state.stb initialize !isMultiline
            }
            if (!isMultiline) {
                if (flags has Itf.AutoSelectAll)
                    selectAll = true
                if (inputRequestedByNav && (!recycleState || g.navActivateFlags hasnt ActivateFlag.TryToPreserveState))
                    selectAll = true
                if (inputRequestedByTabbing || (userClicked && io.keyCtrl))
                    selectAll = true
            }
            if (flags has Itf.AlwaysOverwrite)
                state.stb.insertMode = true // stb field name is indeed incorrect (see #2863)
        }

        if (g.activeId != id && initMakeActive) {
            assert(state!!.id == id)
            setActiveID(id, window)
            setFocusID(id, window)
            focusWindow(window)

        }
        if (g.activeId == id) {
            // Declare our inputs
            if (userClicked)
                Key.MouseLeft.setOwner(id)
            g.activeIdUsingNavDirMask = g.activeIdUsingNavDirMask or ((1 shl Dir.Left) or (1 shl Dir.Right))
            if (isMultiline || flags has Itf.CallbackHistory)
                g.activeIdUsingNavDirMask = g.activeIdUsingNavDirMask or ((1 shl Dir.Up) or (1 shl Dir.Down))
            Key.Escape.setOwner(id)
            Key._NavGamepadCancel.setOwner(id)
            Key.Home.setOwner(id)
            Key.End.setOwner(id)
            if (isMultiline) {
                Key.PageUp.setOwner(id)
                Key.PageDown.setOwner(id)
            }
            if (flags has (Itf.CallbackCompletion or Itf.AllowTabInput))  // Disable keyboard tabbing out as we will use the \t character.
                Key.Tab.setOwner(id)
        }

        // We have an edge case if ActiveId was set through another widget (e.g. widget being swapped), clear id immediately (don't wait until the end of the function)
        if (g.activeId == id && state == null)
            clearActiveID()

        // Release focus when we click outside
        if (g.activeId == id && io.mouseClicked[0] && !initState && !initMakeActive)
            clearActiveId = true

        // Lock the decision of whether we are going to take the path displaying the cursor or selection
        var renderCursor = g.activeId == id || (state != null && userScrollActive)
        var renderSelection = state != null && (state.hasSelection || selectAll) && (RENDER_SELECTION_WHEN_INACTIVE || renderCursor)
        var valueChanged = false
        var validated = false

        // When read-only we always use the live data passed to the function
        // FIXME-OPT: Because our selection/cursor code currently needs the wide text we need to convert it when active, which is not ideal :(
        if (isReadOnly && state != null && (renderCursor || renderSelection)) {
            if (state.textW.size < buf.size)
                state.textW = CharArray(buf.size)
            else if (state.textW.size > buf.size)
                state.textW[buf.size] = NUL
            state.curLenW = textStrFromUtf8(state.textW, buf, textRemaining = bufEnd(0))
            state.curLenA = bufEnd.x
            state.cursorClamp()
            renderSelection = renderSelection && state.hasSelection
        }

        // Select the buffer to render.
        val bufDisplayFromState = (renderCursor || renderSelection || g.activeId == id) && !isReadOnly && state?.textAIsValid == true
        val isDisplayingHint = hint != null && (if (bufDisplayFromState) state!!.textA else buf)[0] == 0.b

        // Password pushes a temporary font with only a fallback glyph
        if (isPassword && !isDisplayingHint)
            g.inputTextPasswordFont.apply {
                val glyph = g.font.findGlyph('*')!!
                fontSize = g.font.fontSize
                scale = g.font.scale
                ascent = g.font.ascent
                descent = g.font.descent
                containerAtlas = g.font.containerAtlas
                fallbackGlyph = glyph
                fallbackAdvanceX = glyph.advanceX
                assert(glyphs.isEmpty() && indexAdvanceX.isEmpty() && indexLookup.isEmpty())
                pushFont(this)
            }

        // Process mouse inputs and character inputs
        var backupCurrentTextLength = 0
        if (g.activeId == id) {

            backupCurrentTextLength = state!!.curLenA
            state.apply {
                edited = false
                bufCapacityA = buf.size
                this.flags = flags
            }
            // Although we are active we don't prevent mouse from hovering other elements unless we are interacting right now with the widget.
            // Down the line we should have a cleaner library-wide concept of Selected vs Active.
            g.activeIdAllowOverlap = !io.mouseDown[0]
            g.wantTextInputNextFrame = 1

            // Edit in progress
            val mouseX = io.mousePos.x - frameBb.min.x - style.framePadding.x + state.scrollX
            val mouseY = when {
                isMultiline -> io.mousePos.y - drawWindow.dc.cursorPos.y
                else -> g.fontSize * 0.5f
            }

            val isOsx = io.configMacOSXBehaviors
            if (selectAll) {
                state.selectAll()
                state.selectedAllMouseLock = true
            } else if (hovered && io.mouseClickedCount[0] >= 2 && !io.keyShift) {
                state.click(mouseX, mouseY)
                val multiclickCount = io.mouseClickedCount[0] - 2
                if (multiclickCount % 2 == 0) {
                    // Double-click: Select word
                    // We always use the "Mac" word advance for double-click select vs CTRL+Right which use the platform dependent variant:
                    // FIXME: There are likely many ways to improve this behavior, but there's no "right" behavior (depends on use-case, software, OS)
                    val isBol = state.stb.cursor == 0 || state.getChar(state.stb.cursor - 1) == '\n'
                    if (state.hasSelection || !isBol)
                        state.onKeyPressed(K.WORDLEFT)
                    //state->OnKeyPressed(STB_TEXTEDIT_K_WORDRIGHT | STB_TEXTEDIT_K_SHIFT);
                    if (!state.hasSelection)
                        state.stb.prepSelectionAtCursor()
                    state.stb.cursor = state.moveWordRight_MAC(state.stb.cursor)
                    state.stb.selectEnd = state.stb.cursor
                    state.clamp()
                } else {
                    // Triple-click: Select line
                    val isEol = state.getChar(state.stb.cursor) == '\n'
                    state.onKeyPressed(K.LINESTART)
                    state.onKeyPressed(K.LINEEND or K.SHIFT)
                    state.onKeyPressed(K.RIGHT or K.SHIFT)
                    if (!isEol && isMultiline) {
                        val swap = state.stb.selectStart
                        state.stb.selectStart = state.stb.selectEnd
                        state.stb.selectEnd = swap
                        state.stb.cursor = state.stb.selectEnd
                    }
                    state.cursorFollow = false
                }
                state.cursorAnimReset()
            } else if (io.mouseClicked[0] && !state.selectedAllMouseLock) {
                if (hovered) {
                    if (io.keyShift)
                        state.drag(mouseX, mouseY)
                    else
                        state.click(mouseX, mouseY)
                    state.cursorAnimReset()
                }
            } else if (io.mouseDown[0] && !state.selectedAllMouseLock && (io.mouseDelta.x != 0f || io.mouseDelta.y != 0f)) { // TODO -> glm once anyNotEqual gets fixed
                state.drag(mouseX, mouseY)
                state.cursorAnimReset()
                state.cursorFollow = true
            }
            if (state.selectedAllMouseLock && !io.mouseDown[0])
                state.selectedAllMouseLock = false

            // We expect backends to emit a Tab key but some also emit a Tab character which we ignore (#2467, #1336)
            // (For Tab and Enter: Win32/SFML/Allegro are sending both keys and chars, GLFW and SDL are only sending keys. For Space they all send all threes)
            val ignoreCharInputs = (io.keyCtrl && !io.keyAlt) || (isOsx && io.keySuper)
            if (flags has Itf.AllowTabInput && Key.Tab.isPressed && !ignoreCharInputs && !io.keyShift && !isReadOnly)
                withChar {
                    it.set('\t') // Insert TAB
                    if (inputTextFilterCharacter(it, flags, callback, callbackUserData, InputSource.Keyboard))
                        state.onKeyPressed(it().i)
                }

            // Process regular text input (before we check for Return because using some IME will effectively send a Return?)
            // We ignore CTRL inputs, but need to allow ALT+CTRL as some keyboards (e.g. German) use AltGR (which _is_ Alt+Ctrl) to input certain characters.
            if (io.inputQueueCharacters.isNotEmpty()) {
                if (!ignoreCharInputs && !isReadOnly && !inputRequestedByNav)
                // Insert character if they pass filtering
                    io.inputQueueCharacters.filter { it != NUL || it == '\t' } // Skip Tab, see above.
                            .forEach {
                                // TODO check
                                withChar { c ->
                                    if (inputTextFilterCharacter(c(it), flags, callback, callbackUserData, InputSource.Keyboard))
                                        state.onKeyPressed(c().i)
                                }
                            }
                // Consume characters
                io.inputQueueCharacters.clear()
            }
        }

        // Process other shortcuts/key-presses
        var revertEdit = false
        if (g.activeId == id && !g.activeIdIsJustActivated && !clearActiveId) {
            state!! // ~IM_ASSERT(state != NULL);

            val rowCountPerPage = ((innerSize.y - style.framePadding.y) / g.fontSize).i max 1
            state.stb.rowCountPerPage = rowCountPerPage

            val kMask = if (io.keyShift) K.SHIFT else 0
            val isOsx = io.configMacOSXBehaviors
            // OS X style: Shortcuts using Cmd/Super instead of Ctrl
            val isOsxShiftShortcut = isOsx && io.keyMods == Key.Mod_Super or Key.Mod_Shift
            val isWordmoveKeyDown = if (isOsx) io.keyAlt else io.keyCtrl // OS X style: Text editing cursor movement using Alt instead of Ctrl
            // OS X style: Line/Text Start and End using Cmd+Arrows instead of Home/End
            val isStartendKeyDown = isOsx && io.keySuper && !io.keyCtrl && !io.keyAlt
            val isCtrlKeyOnly = io.keyMods == Key.Mod_Ctrl.i
            val isShiftKeyOnly = io.keyMods == Key.Mod_Shift.i
            val isShortcutKey = io.keyMods == if (io.configMacOSXBehaviors) Key.Mod_Super.i else Key.Mod_Ctrl.i

            val isCut = ((isShortcutKey && Key.X.isPressed) || (isShiftKeyOnly && Key.Delete.isPressed)) && !isReadOnly && !isPassword && (!isMultiline || state.hasSelection)
            val isCopy = ((isShortcutKey && Key.C.isPressed(false)) || (isCtrlKeyOnly && Key.Insert.isPressed(false))) && !isPassword && (!isMultiline || state.hasSelection)
            val isPaste = ((isShortcutKey && Key.V.isPressed) || (isShiftKeyOnly && Key.Insert.isPressed)) && !isReadOnly
            val isUndo = ((isShortcutKey && Key.Z.isPressed) && !isReadOnly && isUndoable)
            val isRedo = ((isShortcutKey && Key.Y.isPressed) || (isOsxShiftShortcut && Key.Z.isPressed)) && !isReadOnly && isUndoable
            val isSelectAll = isShortcutKey && Key.A.isPressed(false)

            // We allow validate/cancel with Nav source (gamepad) to makes it easier to undo an accidental NavInput press with no keyboard wired, but otherwise it isn't very useful.
            val navGamepadActive = io.configFlags has ConfigFlag.NavEnableGamepad && io.backendFlags has BackendFlag.HasGamepad
            val isEnterPressed = Key.Enter.isPressed(true) || Key.KeypadEnter.isPressed(true)
            val isGamepadValidate = navGamepadActive && (Key._NavGamepadActivate.isPressed(false) || Key._NavGamepadInput.isPressed(false))
            val isCancel = Key.Escape.isPressed(false) || (navGamepadActive && Key._NavGamepadCancel.isPressed(false))

            when {
                Key.LeftArrow.isPressed -> state.onKeyPressed(
                    when {
                        isStartendKeyDown -> K.LINESTART
                        isWordmoveKeyDown -> K.WORDLEFT
                        else -> K.LEFT
                    } or kMask)
                Key.RightArrow.isPressed -> state.onKeyPressed(
                    when {
                        isStartendKeyDown -> K.LINEEND
                        isWordmoveKeyDown -> K.WORDRIGHT
                        else -> K.RIGHT
                    } or kMask)
                Key.UpArrow.isPressed && isMultiline ->
                    if (io.keyCtrl)
                        drawWindow setScrollY glm.max(drawWindow.scroll.y - g.fontSize, 0f)
                    else
                        state.onKeyPressed((if (isStartendKeyDown) K.TEXTSTART else K.UP) or kMask)
                Key.DownArrow.isPressed && isMultiline ->
                    if (io.keyCtrl)
                        drawWindow setScrollY glm.min(drawWindow.scroll.y + g.fontSize, scrollMaxY)
                    else
                        state.onKeyPressed((if (isStartendKeyDown) K.TEXTEND else K.DOWN) or kMask)
                Key.PageUp.isPressed && isMultiline -> {
                    state.onKeyPressed(K.PGUP or kMask)
                    scrollY -= rowCountPerPage * g.fontSize
                }
                Key.PageDown.isPressed && isMultiline -> {
                    state.onKeyPressed(K.PGDOWN or kMask)
                    scrollY += rowCountPerPage * g.fontSize
                }
                Key.Home.isPressed -> state.onKeyPressed((if (io.keyCtrl) K.TEXTSTART else K.LINESTART) or kMask)
                Key.End.isPressed -> state.onKeyPressed((if (io.keyCtrl) K.TEXTEND else K.LINEEND) or kMask)
                Key.Delete.isPressed && !isReadOnly && !isCut -> state.onKeyPressed(K.DELETE or kMask)
                Key.Backspace.isPressed && !isReadOnly -> {
                    if (!state.hasSelection)
                        if (isWordmoveKeyDown)
                            state.onKeyPressed(K.WORDLEFT or K.SHIFT)
                        else if (isOsx && io.keySuper && !io.keyAlt && !io.keyCtrl)
                            state.onKeyPressed(K.LINESTART or K.SHIFT)
                    state.onKeyPressed(K.BACKSPACE or kMask)
                }
                isEnterPressed || isGamepadValidate -> {
                    // Determine if we turn Enter into a \n character
                    val ctrlEnterForNewLine = flags has Itf.CtrlEnterForNewLine
                    if (!isMultiline || isGamepadValidate || (ctrlEnterForNewLine && !io.keyCtrl) || (!ctrlEnterForNewLine && io.keyCtrl)) {
                        validated = true
                        if (io.configInputTextEnterKeepActive && !isMultiline)
                            state.selectAll() // No need to scroll
                        else
                            clearActiveId = true

                    } else if (!isReadOnly)
                        withChar('\n') { c ->
                            // Insert new line
                            if (inputTextFilterCharacter(c, flags, callback, callbackUserData, InputSource.Keyboard))
                                state.onKeyPressed(c().i)
                        }
                }
                isCancel ->
                    if (flags has Itf.EscapeClearsAll) {
                        if (state.curLenA > 0)
                            revertEdit = true
                        else {
                            renderCursor = false; renderSelection = false
                            clearActiveId = true
                        }
                    } else {
                        clearActiveId = true; revertEdit = true
                        renderCursor = false; renderSelection = false
                    }
                isUndo || isRedo -> {
                    state.onKeyPressed(if (isUndo) K.UNDO else K.REDO)
                    state.clearSelection()
                }
                isSelectAll -> {
                    state.selectAll()
                    state.cursorFollow = true
                }
                isCut || isCopy -> {
                    // Cut, Copy
                    io.setClipboardTextFn?.let {
                        val ib = if (state.hasSelection) min(state.stb.selectStart, state.stb.selectEnd) else 0
                        val ie =
                            if (state.hasSelection) max(state.stb.selectStart, state.stb.selectEnd) else state.curLenW
                        clipboardText = String(state.textW, ib, ie - ib)
                    }
                    if (isCut) {
                        if (!state.hasSelection)
                            state.selectAll()
                        state.cursorFollow = true
                        state.cut()
                    }
                }
                isPaste -> {

                    val clipboard = clipboardText

                    // Filter pasted buffer
                    val clipboardLen = clipboard.length
                    val clipboardFiltered = CharArray(clipboardLen)
                    var clipboardFilteredLen = 0
                    for (c in clipboard) {
                        if (c == NUL)
                            break
                        _c = c
                        if (!inputTextFilterCharacter(::_c, flags, callback, callbackUserData, InputSource.Keyboard))
                            continue
                        clipboardFiltered[clipboardFilteredLen++] = _c
                    }
                    if (clipboardFilteredLen > 0) { // If everything was filtered, ignore the pasting operation
                        state.paste(clipboardFiltered, clipboardFilteredLen)
                        state.cursorFollow = true
                    }
                }
            }

            // Update render selection flag after events have been handled, so selection highlight can be displayed during the same frame.
            renderSelection = renderSelection || (state.hasSelection && (RENDER_SELECTION_WHEN_INACTIVE || renderCursor))
        }

        // Process callbacks and apply result back to user's buffer.
        var applyNewText: ByteArray? = null
        var applyNewTextLength = 0
        if (g.activeId == id) {

            state!!
            if (revertEdit && !isReadOnly) {
                if (flags has Itf.EscapeClearsAll) {
                    // Clear input
                    applyNewText = ByteArray(0)
                    applyNewTextLength = 0
                    val emptyString = CharArray(0)
                    state.replace(emptyString, 0)
                }
                else if (buf.strcmp(state.initialTextA) != 0) {
                    // Restore initial value. Only return true if restoring to the initial value changes the current buffer contents.
                    // Push records into the undo stack so we can CTRL+Z the revert operation itself
                    applyNewText = state.initialTextA
                    applyNewTextLength = state.initialTextA.size

                    var wText = CharArray(0)
                    if (applyNewTextLength > 0) {
                        wText = CharArray(textCountCharsFromUtf8(applyNewText, applyNewTextLength))
                        textStrFromUtf8(wText, applyNewText, applyNewTextLength)
                    }
                    state.replace(wText, if (applyNewTextLength > 0) wText.size else 0)
                }
            }

            // Apply ASCII value
            if (!isReadOnly) {
                state.textAIsValid = true
                if (state.textA.size < state.textW.size * 4)
                    state.textA = ByteArray(state.textW.size * 4)
                else if (state.textA.size > state.textW.size * 4)
                    state.textA[state.textW.size * 4] = 0
                textStrToUtf8(state.textA, state.textW)
            }

            // When using 'ImGuiInputTextFlags_EnterReturnsTrue' as a special case we reapply the live buffer back to the input buffer before clearing ActiveId, even though strictly speaking it wasn't modified on this frame.
            // If we didn't do that, code like InputInt() with ImGuiInputTextFlags_EnterReturnsTrue would fail.
            // This also allows the user to use InputText() with ImGuiInputTextFlags_EnterReturnsTrue without maintaining any user-side storage (please note that if you use this property along ImGuiInputTextFlags_CallbackResize you can end up with your temporary string object unnecessarily allocating once a frame, either store your string data, either if you don't then don't use ImGuiInputTextFlags_CallbackResize).
            val applyEditBackToUserBuffer = !revertEdit || (validated && flags hasnt Itf.EnterReturnsTrue)
            if (applyEditBackToUserBuffer) {
                // Apply new value immediately - copy modified buffer back
                // Note that as soon as the input box is active, the in-widget value gets priority over any underlying modification of the input buffer
                // FIXME: We actually always render 'buf' when calling DrawList->AddText, making the comment above incorrect.
                // FIXME-OPT: CPU waste to do this every time the widget is active, should mark dirty state from the stb_textedit callbacks.


                // User callback
                if (flags has (Itf.CallbackCompletion or Itf.CallbackHistory or Itf.CallbackAlways)) {
                    callback!!
                    // The reason we specify the usage semantic (Completion/History) is that Completion needs to disable keyboard TABBING at the moment.
                    var eventFlag = Itf.None
                    var eventKey = Key.None
                    when {
                        flags has Itf.CallbackCompletion && Key.Tab.isPressed -> {
                            eventFlag = Itf.CallbackCompletion
                            eventKey = Key.Tab
                        }
                        flags has Itf.CallbackHistory && Key.UpArrow.isPressed -> {
                            eventFlag = Itf.CallbackHistory
                            eventKey = Key.UpArrow
                        }
                        flags has Itf.CallbackHistory && Key.DownArrow.isPressed -> {
                            eventFlag = Itf.CallbackHistory
                            eventKey = Key.DownArrow
                        }
                        flags has Itf.CallbackEdit && state.edited -> eventFlag = Itf.CallbackEdit
                        flags has Itf.CallbackAlways -> eventFlag = Itf.CallbackAlways
                    }

                    if (eventFlag != Itf.None) {
                        val cbData = InputTextCallbackData()
                        cbData.eventFlag = eventFlag.i
                        cbData.flags = flags
                        cbData.userData = callbackUserData

                        var callbackData = if (isReadOnly) buf else state.textA
                        cbData.eventKey = eventKey
                        cbData.buf = callbackData
                        cbData.bufTextLen = state.curLenA
                        cbData.bufSize = state.bufCapacityA
                        cbData.bufDirty = false

                        // We have to convert from wchar-positions to UTF-8-positions, which can be pretty slow (an incentive to ditch the ImWchar buffer, see https://github.com/nothings/stb/issues/188)
                        val text = state.textW
                        cbData.cursorPos = textCountUtf8BytesFromStr(text, state.stb.cursor)
                        val utf8CursorPos = cbData.cursorPos
                        cbData.selectionStart = textCountUtf8BytesFromStr(text, state.stb.selectStart)
                        val utf8SelectionStart = cbData.selectionStart
                        cbData.selectionEnd = textCountUtf8BytesFromStr(text, state.stb.selectEnd)
                        val utf8SelectionEnd = cbData.selectionEnd

                        // Call user code
                        callback(cbData)

                        // Read back what user may have modified
                        callbackData = if (isReadOnly) buf else state.textA // Pointer may have been invalidated by a resize callback
                        assert(cbData.buf === callbackData) { "Invalid to modify those fields" }
                        assert(cbData.bufSize == state.bufCapacityA)
                        assert(cbData.flags == flags)
                        val bufDirty = cbData.bufDirty
                        if (cbData.cursorPos != utf8CursorPos || bufDirty) {
                            state.stb.cursor = textCountCharsFromUtf8(cbData.buf, cbData.cursorPos)
                            state.cursorFollow = true
                        }
                        if (cbData.selectionStart != utf8SelectionStart || bufDirty)
                            state.stb.selectStart = when {
                                cbData.selectionStart == cbData.cursorPos -> state.stb.cursor
                                else -> textCountCharsFromUtf8(cbData.buf, cbData.selectionStart)
                            }
                        if (cbData.selectionEnd != utf8SelectionEnd || bufDirty)
                            state.stb.selectEnd = when {
                                cbData.selectionEnd == cbData.selectionStart -> state.stb.selectStart
                                else -> textCountCharsFromUtf8(cbData.buf, cbData.selectionEnd)
                            }
                        if (bufDirty) {
                            assert(flags hasnt Itf.ReadOnly)
                            assert(cbData.bufTextLen == cbData.buf.strlen()) { "You need to maintain BufTextLen if you change the text!" }
                            inputTextReconcileUndoStateAfterUserCallback(state, callbackData, callbackData.size) // FIXME: Move the rest of this block inside function and rename to InputTextReconcileStateAfterUserCallback() ?
                            if ((cbData.bufTextLen > backupCurrentTextLength) and isResizable) {
                                val newSize = state.textW.size + (cbData.bufTextLen - backupCurrentTextLength) // Worse case scenario resize
                                if (state.textW.size < newSize)
                                    state.textW = CharArray(newSize)
                                else if (state.textW.size > newSize)
                                    state.textW[newSize] = NUL
                            }
                            state.curLenW = textStrFromUtf8(state.textW, cbData.buf)
                            state.curLenA =
                                cbData.bufTextLen  // Assume correct length and valid UTF-8 from user, saves us an extra strlen()
                            state.cursorAnimReset()
                        }
                    }
                }
                // Will copy result string if modified
                if (!isReadOnly && state.textA strcmp buf != 0) {
                    applyNewText = state.textA
                    applyNewTextLength = state.curLenA
                }
            }
        }

        // Copy result to user buffer
        if (applyNewText != null) {
            // We cannot test for 'backup_current_text_length != apply_new_text_length' here because we have no guarantee that the size
            // of our owned buffer matches the size of the string object held by the user, and by design we allow InputText() to be used
            // without any storage on user's side.
            assert(applyNewTextLength >= 0)
            if (isResizable) {
                val callbackData = InputTextCallbackData().also {
                    it.eventFlag = Itf.CallbackResize.i
                    it.flags = flags
                    it.buf = buf
                    it.bufTextLen = applyNewTextLength
                    it.bufSize = buf.size max applyNewTextLength
                    it.userData = callbackUserData
                }
                callback!!(callbackData)
                buf = callbackData.buf
                //                    buf_size = callback_data.BufSize; TODO?
                applyNewTextLength = callbackData.bufTextLen min buf.size
                assert(applyNewTextLength <= buf.size)
            }
            //IMGUI_DEBUG_PRINT("InputText(\"%s\"): apply_new_text length %d\n", label, apply_new_text_length);

            // If the underlying buffer resize was denied or not carried to the next frame, apply_new_text_length+1 may be >= buf_size.
            System.arraycopy(applyNewText, 0, buf, 0, applyNewTextLength min buf.size)
            if (applyNewTextLength < buf.size) buf[applyNewTextLength] = 0
            valueChanged = true
        }

        // Release active ID at the end of the function (so e.g. pressing Return still does a final application of the value)
        if (clearActiveId && g.activeId == id) clearActiveID()

        // Render frame
        if (!isMultiline) {
            renderNavHighlight(frameBb, id)
            renderFrame(frameBb.min, frameBb.max, Col.FrameBg.u32, true, style.frameRounding)
        }

        val clipRect = Vec4(frameBb.min, frameBb.min + innerSize) // Not using frameBb.Max because we have adjusted size
        val drawPos = if (isMultiline) Vec2(drawWindow.dc.cursorPos) else frameBb.min + style.framePadding
        val textSize = Vec2()

        /*  Set upper limit of single-line InputTextEx() at 2 million characters strings. The current pathological worst case is a long line
            without any carriage return, which would makes ImFont::RenderText() reserve too many vertices and probably crash. Avoid it altogether.
            Note that we only use this limit on single-line InputText(), so a pathologically large line on a InputTextMultiline() would still crash. */
        val bufDisplayMaxLength = 2 * 1024 * 1024
        var bufDisplay = if (bufDisplayFromState) state!!.textA else buf
        var bufDisplayEnd = -1 // We have specialized paths below for setting the length
        if (isDisplayingHint) {
            bufDisplay = hint!!.toByteArray()
            bufDisplayEnd = hint.length
        }

        // Render text. We currently only render selection when the widget is active or while scrolling.
        // FIXME: We could remove the '&& render_cursor' to keep rendering selection when inactive.
        if (renderCursor || renderSelection) {

            state!!
            if (!isDisplayingHint)
                bufDisplayEnd = state.curLenA

            /*  Render text (with cursor and selection)
                This is going to be messy. We need to:
                    - Display the text (this alone can be more easily clipped)
                    - Handle scrolling, highlight selection, display cursor (those all requires some form of 1d->2d
                        cursor position calculation)
                    - Measure text height (for scrollbar)
                We are attempting to do most of that in **one main pass** to minimize the computation cost
                (non-negligible for large amount of text) + 2nd pass for selection rendering (we could merge them by an
                extra refactoring effort)   */
            // FIXME: This should occur on bufDisplay but we'd need to maintain cursor/select_start/select_end for UTF-8.
            val text = state.textW
            val cursorOffset = Vec2()
            val selectStartOffset = Vec2()

            run {
                // Find lines numbers straddling 'cursor' (slot 0) and 'select_start' (slot 1) positions.
                val searchesInputPtr = IntArray(2) { -1 }
                val searchesResultLineNo = intArrayOf(-1000, -1000)
                var searchesRemaining = 0
                if (renderCursor) {
                    searchesInputPtr[0] = state.stb.cursor
                    searchesResultLineNo[0] = -1
                    searchesRemaining++
                }
                if (renderSelection) {
                    searchesInputPtr[1] = state.stb.selectStart min state.stb.selectEnd
                    searchesResultLineNo[1] = -1
                    searchesRemaining++
                }

                // Iterate all lines to find our line numbers
                // In multi-line mode, we never exit the loop until all lines are counted, so add one extra to the searchesRemaining counter.
                if (isMultiline) searchesRemaining++
                var lineCount = 0
                var s = 0
                while (s < text.size && text[s] != NUL)
                    if (text[s++] == '\n') {
                        lineCount++
                        if (searchesResultLineNo[0] == -1 && s > searchesInputPtr[0]) {
                            searchesResultLineNo[0] = lineCount; if (--searchesRemaining <= 0) break
                        }
                        if (searchesResultLineNo[1] == -1 && s > searchesInputPtr[1]) {
                            searchesResultLineNo[1] = lineCount; if (--searchesRemaining <= 0) break
                        }
                    }
                lineCount++
                if (searchesResultLineNo[0] == -1)
                    searchesResultLineNo[0] = lineCount
                if (searchesResultLineNo[1] == -1)
                    searchesResultLineNo[1] = lineCount

                // Calculate 2d position by finding the beginning of the line and measuring distance
                var start = text beginOfLine searchesInputPtr[0]
                cursorOffset.x = inputTextCalcTextSizeW(text, start, searchesInputPtr[0]).x
                cursorOffset.y = searchesResultLineNo[0] * g.fontSize
                if (searchesResultLineNo[1] >= 0) {
                    start = text beginOfLine searchesInputPtr[1]
                    selectStartOffset.x = inputTextCalcTextSizeW(text, start, searchesInputPtr[1]).x
                    selectStartOffset.y = searchesResultLineNo[1] * g.fontSize
                }

                // Store text height (note that we haven't calculated text width at all, see GitHub issues #383, #1224)
                if (isMultiline)
                    textSize.put(innerSize.x, lineCount * g.fontSize)
            }

            // Scroll
            if (renderCursor && state.cursorFollow) {
                // Horizontal scroll in chunks of quarter width
                if (flags hasnt Itf.NoHorizontalScroll) {
                    val scrollIncrementX = innerSize.x * 0.25f
                    val visibleWidth = innerSize.x - style.framePadding.x
                    if (cursorOffset.x < state.scrollX)
                        state.scrollX = floor(glm.max(0f, cursorOffset.x - scrollIncrementX))
                    else if (cursorOffset.x - visibleWidth >= state.scrollX)
                        state.scrollX = floor(cursorOffset.x - visibleWidth + scrollIncrementX)
                } else
                    state.scrollX = 0f

                // Vertical scroll
                if (isMultiline) {
                    // Test if cursor is vertically visible
                    if (cursorOffset.y - g.fontSize < scrollY)
                        scrollY = glm.max(0f, cursorOffset.y - g.fontSize)
                    else if (cursorOffset.y - (innerSize.y - style.framePadding.y * 2f) >= scrollY)
                        scrollY = cursorOffset.y - innerSize.y + style.framePadding.y * 2f
                    val scrollMaxY = ((textSize.y + style.framePadding.y * 2f) - innerSize.y) max 0f
                    scrollY = clamp(scrollY, 0f, scrollMaxY)
                    drawPos.y += drawWindow.scroll.y - scrollY   // Manipulate cursor pos immediately avoid a frame of lag
                    drawWindow.scroll.y = scrollY
                }

                state.cursorFollow = false
            }

            // Draw selection
            val drawScroll = Vec2(state.scrollX, 0f)
            if (renderSelection) {

                val textSelectedBegin = state.stb.selectStart min state.stb.selectEnd
                val textSelectedEnd = state.stb.selectStart max state.stb.selectEnd

                val bgColor = getColorU32(Col.TextSelectedBg, if (renderCursor) 1f else 0.6f) // FIXME: current code flow mandate that render_cursor is always true here, we are leaving the transparent one for tests.
                val bgOffYUp = if (isMultiline) 0f else -1f // FIXME: those offsets should be part of the style? they don't play so well with multi-line selection.
                val bgOffYDn = if (isMultiline) 0f else 2f
                val rectPos = drawPos + selectStartOffset - drawScroll
                var p = textSelectedBegin
                while (p < textSelectedEnd) {
                    if (rectPos.y > clipRect.w + g.fontSize) break
                    if (rectPos.y < clipRect.y) {
                        while (p < textSelectedEnd)
                            if (text[p++] == '\n')
                                break
                    } else {
                        val rectSize = withInt {
                            inputTextCalcTextSizeW(text, p, textSelectedEnd, it, stopOnNewLine = true).also { p = it() }
                        }
                        // So we can see selected empty lines
                        if (rectSize.x <= 0f) rectSize.x = floor(g.font.getCharAdvance(' ') * 0.5f)
                        val rect = Rect(rectPos + Vec2(0f, bgOffYUp - g.fontSize), rectPos + Vec2(rectSize.x, bgOffYDn))
                        val clipRect_ = Rect(clipRect)
                        rect clipWith clipRect_
                        if (rect overlaps clipRect_)
                            drawWindow.drawList.addRectFilled(rect.min, rect.max, bgColor)
                    }
                    rectPos.x = drawPos.x - drawScroll.x
                    rectPos.y += g.fontSize
                }
            }

            // We test for 'buf_display_max_length' as a way to avoid some pathological cases (e.g. single-line 1 MB string) which would make ImDrawList crash.
            if (isMultiline || bufDisplayEnd < bufDisplayMaxLength) {
                val col = getColorU32(if (isDisplayingHint) Col.TextDisabled else Col.Text)
                drawWindow.drawList.addText(g.font, g.fontSize, drawPos - drawScroll, col, bufDisplay, 0,
                                            bufDisplayEnd, 0f, clipRect.takeUnless { isMultiline })
            }

            // Draw blinking cursor
            if (renderCursor) {
                state.cursorAnim += io.deltaTime
                val cursorIsVisible = !io.configInputTextCursorBlink || state.cursorAnim <= 0f || glm.mod(state.cursorAnim, 1.2f) <= 0.8f
                val cursorScreenPos = floor(drawPos + cursorOffset - drawScroll)
                val cursorScreenRect = Rect(cursorScreenPos.x, cursorScreenPos.y - g.fontSize + 0.5f,
                                            cursorScreenPos.x + 1f, cursorScreenPos.y - 1.5f)
                if (cursorIsVisible && cursorScreenRect overlaps clipRect)
                    drawWindow.drawList.addLine(cursorScreenRect.min, cursorScreenRect.bl, Col.Text.u32)

                // Notify OS of text input position for advanced IME (-1 x offset so that Windows IME can cover our cursor. Bit of an extra nicety.)
                if (!isReadOnly)
                    g.platformImeData.apply {
                        wantVisible = true
                        inputPos.put(cursorScreenPos.x - 1f, cursorScreenPos.y - g.fontSize)
                        inputLineHeight = g.fontSize
                    }
            }
        } else {
            // Render text only (no selection, no cursor)
            if (isMultiline) {
                val (lineCount, textEnd) = inputTextCalcTextLenAndLineCount(bufDisplay)
                bufDisplayEnd = textEnd
                textSize.put(innerSize.x, lineCount * g.fontSize) // We don't need width
            } else if (!isDisplayingHint && g.activeId == id)
                bufDisplayEnd = state!!.curLenA
            else if (!isDisplayingHint)
                bufDisplayEnd = bufDisplay.strlen()

            if (isMultiline || bufDisplayEnd < bufDisplayMaxLength) {
                val col = getColorU32(if (isDisplayingHint) Col.TextDisabled else Col.Text)
                drawWindow.drawList.addText(g.font, g.fontSize, drawPos, col, bufDisplay, 0, bufDisplayEnd,
                                            0f, clipRect.takeUnless { isMultiline })
            }
        }

        if (isPassword && !isDisplayingHint)
            popFont()

        if (isMultiline) {
            // For focus requests to work on our multiline we need to ensure our child ItemAdd() call specifies the ImGuiItemFlags_Inputable (ref issue #4761)...
            dummy(Vec2(textSize.x, textSize.y + style.framePadding.y))
            val backupItemFlags = g.currentItemFlags
            g.currentItemFlags = g.currentItemFlags or (ItemFlag.Inputable or ItemFlag.NoTabStop)
            endChild()
            itemDataBackup.statusFlags = itemDataBackup.statusFlags or (g.lastItemData.statusFlags and ItemStatusFlag.HoveredWindow)
            g.currentItemFlags = backupItemFlags

            // ...and then we need to undo the group overriding last item data, which gets a bit messy as EndGroup() tries to forward scrollbar being active...
            // FIXME: This quite messy/tricky, should attempt to get rid of the child window.
            endGroup()
            if (g.lastItemData.id == 0) {
                g.lastItemData.id = id
                g.lastItemData.inFlags = itemDataBackup.inFlags
                g.lastItemData.statusFlags = itemDataBackup.statusFlags
            }
        }

        // Log as text
        if (g.logEnabled && (!isPassword || isDisplayingHint)) {
            logSetNextTextDecoration("{", "}")
            logRenderedText(drawPos, String(bufDisplay, 0, bufDisplayEnd), bufDisplayEnd)
        }

        if (labelSize.x > 0)
            renderText(Vec2(frameBb.max.x + style.itemInnerSpacing.x, frameBb.min.y + style.framePadding.y), label)

        if (valueChanged && flags hasnt Itf._NoMarkEdited)
            markItemEdited(id)

        IMGUI_TEST_ENGINE_ITEM_INFO(id, label, g.lastItemData.statusFlags)
        return when {
            flags has Itf.EnterReturnsTrue -> validated
            else -> valueChanged
        }
    }

    /** Create text input in place of another active widget (e.g. used when doing a CTRL+Click on drag/slider widgets)
     *  FIXME: Facilitate using this in variety of other situations. */
    fun tempInputText(bb: Rect, id: ID, label: String, buf: ByteArray, flags: InputTextFlags): Boolean {
        // On the first frame, g.TempInputTextId == 0, then on subsequent frames it becomes == id.
        // We clear ActiveID on the first frame to allow the InputText() taking it back.
        val init = g.tempInputId != id

        if (init)
            clearActiveID()

        g.currentWindow!!.dc.cursorPos put bb.min
        val valueChanged = inputTextEx(label, null, buf, bb.size, flags or Itf._MergedItem)
        if (init) {
            // First frame we started displaying the InputText widget, we expect it to take the active id.
            assert(g.activeId == id)
            g.tempInputId = g.activeId
        }
        return valueChanged
    }

    /** Note that Drag/Slider functions are only forwarding the min/max values clamping values if the
     *  ImGuiSliderFlags_AlwaysClamp flag is set!
     *  This is intended: this way we allow CTRL+Click manual input to set a value out of bounds, for maximum flexibility.
     *  However this may not be ideal for all uses, as some user code may break on out of bound values. */
    fun <N> tempInputScalar(bb: Rect, id: ID, label: String, dataType: DataType, pData: KMutableProperty0<N>,
                            format_: String, clampMin_: N? = null, clampMax_: N? = null): Boolean
            where N : Number, N : Comparable<N> {

        var clampMin = clampMin_
        var clampMax = clampMax_

        // On the first frame, g.TempInputTextId == 0, then on subsequent frames it becomes == id.
        // We clear ActiveID on the first frame to allow the InputText() taking it back.
        val init = g.tempInputId != id
        if (init)
            clearActiveID()

        val format = parseFormatTrimDecorations(format_)
        val dataBuf = pData.format(dataType, format)
                .trim()

        var flags = Itf.AutoSelectAll or Itf._NoMarkEdited
        flags /= inputScalarDefaultCharsFilter(dataType, format)

        val buf = dataBuf.toByteArray(32)
        var valueChanged = false
        if (tempInputText(bb, id, label, buf, flags)) {
            // Backup old value
            val dataBackup = pData()

            // Apply new value (or operations) then clamp
            dataTypeApplyFromText(buf.cStr, dataType, pData, format)
            if (clampMin != null && clampMax != null) {
                if (clampMin > clampMax) {
                    val t = clampMin
                    clampMin = clampMax
                    clampMax = t
                }
                dataTypeClamp(dataType, pData, clampMin, clampMax)
            }

            // Only mark as edited if new value is different
            valueChanged = dataBackup != pData()

            if (valueChanged)
                markItemEdited(id)
        }
        return valueChanged
    }

    fun tempInputIsActive(id: ID): Boolean = g.activeId == id && g.tempInputId == id

    fun getInputTextState(id: ID): InputTextState? =
        g.inputTextState.takeIf { id != 0 && it.id == id } // Get input text state if active
}