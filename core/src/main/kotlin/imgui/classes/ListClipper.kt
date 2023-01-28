package imgui.classes

import glm_.i
import glm_.max
import glm_.min
import imgui.Dir
import imgui.ImGui
import imgui.api.g
import imgui.clamp
import imgui.internal.floor
import imgui.internal.sections.ListClipperData
import imgui.internal.sections.ListClipperRange
import kotlin.math.ceil

/** Helper: Manually clip large list of items.
 *  If you have lots evenly spaced items and you have a random access to the list, you can perform coarse
 *  clipping based on visibility to only submit items that are in view.
 *  The clipper calculates the range of visible items and advance the cursor to compensate for the non-visible items we
 *  have skipped.
 *  (Dear ImGui already clip items based on their bounds but: it needs to first layout the item to do so, and generally
 *  fetching/submitting your own data incurs additional cost. Coarse clipping using ImGuiListClipper allows you to easily
 *  scale using lists with tens of thousands of items without a problem)
 *  coarse clipping before submission makes this cost and your own data fetching/submission cost almost null)
 *    ImGuiListClipper clipper;
 *    clipper.Begin(1000);         // We have 1000 elements, evenly spaced.
 *    while (clipper.Step())
 *        for (int i = clipper.DisplayStart; i < clipper.DisplayEnd; i++)
 *            ImGui::Text("line number %d", i);
 *  Generally what happens is:
 *  - Clipper lets you process the first element (DisplayStart = 0, DisplayEnd = 1) regardless of it being visible or not.
 *  - User code submit that one element.
 *  - Clipper can measure the height of the first element
 *  - Clipper calculate the actual range of elements to display based on the current clipping rectangle, position the cursor before the first visible element.
 *  - User code submit visible elements.
 *  - The clipper also handles various subtleties related to keyboard/gamepad navigation, wrapping etc. */
class ListClipper {

    var displayStart = 0
    var displayEnd = 0
    val display
        get() = displayStart until displayEnd
    var itemsCount = -1
    var itemsHeight = 0f // [Internal] Height of item after a first step and item submission can calculate it
    var startPosY = 0f // [Internal] Cursor position at the time of Begin() or after table frozen rows are all processed
    var tempData: Any? = null // [Internal] Internal data

    fun dispose() {
        assert(itemsCount == -1) { "Forgot to call End(), or to Step() until false?" }
        end()
    }

    fun begin(itemsCount: Int, itemsHeight: Float = -1f) {

        val window = g.currentWindow!!

        g.currentTable?.let { table ->
            if (table.isInsideRow)
                table.endRow()
        }

        startPosY = window.dc.cursorPos.y
        this.itemsHeight = itemsHeight
        this.itemsCount = itemsCount
        displayStart = -1
        displayEnd = 0

        // Acquire temporary buffer
        if (++g.clipperTempDataStacked > g.clipperTempData.size)
            for (i in g.clipperTempData.size until g.clipperTempDataStacked)
                g.clipperTempData += ListClipperData()
        val data = g.clipperTempData[g.clipperTempDataStacked - 1]
        data.reset(this)
        tempData = data
    }

    /** Automatically called on the last call of Step() that returns false. */
    fun end() {

        // In theory here we should assert that we are already at the right position, but it seems saner to just seek at the end and not assert/crash the user.
        if (itemsCount >= 0 && itemsCount < Int.MAX_VALUE && displayStart >= 0)
            seekCursorForItem(this, itemsCount)
        itemsCount = -1

        // Restore temporary buffer and fix back pointers which may be invalidated when nesting
        assert(g.clipperTempDataStacked > 0)
        (tempData as? ListClipperData)?.let { data ->
            assert(data.listClipper == this)
            data.stepNo = data.ranges.size
            if (--g.clipperTempDataStacked > 0) {
                tempData = g.clipperTempData[g.clipperTempDataStacked - 1].apply {
                    listClipper.tempData = tempData
                }
            }
            tempData = null
        }
    }

    /** Call until it returns false. The DisplayStart/DisplayEnd fields will be set and you can process/draw those items.  */
    fun step(): Boolean {

        val window = g.currentWindow!!
        val data = tempData as ListClipperData

        val table = g.currentTable
        if (table != null && table.isInsideRow)
            table.endRow()

        // Reached end of list
        if (itemsCount == 0 || skipItemForListClipping) {
            end()
            return false
        }

        // While we are in frozen row state, keep displaying items one by one, unclipped
        // FIXME: Could be stored as a table-agnostic state.
        if (data.stepNo == 0 && table != null && !table.isUnfrozenRows) {
            displayStart = data.itemsFrozen
            displayEnd = data.itemsFrozen + 1
            data.itemsFrozen++
            return true
        }

        // Step 0: Let you process the first element (regardless of it being visible or not, so we can measure the element height)
        var calcClipping = false
        if (data.stepNo == 0) {
            startPosY = window.dc.cursorPos.y
            if (itemsHeight <= 0f) {
                // Submit the first item (or range) so we can measure its height (generally the first range is 0..1)
                data.ranges += ListClipperRange.fromIndices(data.itemsFrozen, data.itemsFrozen + 1)
                displayStart = data.ranges[0].min max data.itemsFrozen
                displayEnd = data.ranges[0].max min itemsCount
                data.stepNo = 1
                return true
            }
            calcClipping = true // If on the first step with known item height, calculate clipping.
        }

        // Step 1: Let the clipper infer height from first range
        if (itemsHeight <= 0f) {
            assert(data.stepNo == 1)
            if (table != null) {
                val posY1 = table.rowPosY1   // Using RowPosY1 instead of StartPosY to handle clipper straddling the frozen row
                val posY2 = table.rowPosY2   // Using RowPosY2 instead of CursorPos.y to take account of tallest cell.
                itemsHeight = posY2 - posY1
                window.dc.cursorPos.y = posY2
            } else
                itemsHeight = (window.dc.cursorPos.y - startPosY) / (displayEnd - displayStart)
            assert(itemsHeight > 0f) { "Unable to calculate item height! First item hasn't moved the cursor vertically!" }
            calcClipping = true // If item height had to be calculated, calculate clipping afterwards.
        }

        // Step 0 or 1: Calculate the actual ranges of visible elements.
        var alreadySubmitted = displayEnd
        if (calcClipping) {
            if (g.logEnabled)
            // If logging is active, do not perform any clipping
                data.ranges += ListClipperRange.fromIndices(0, itemsCount)
            else {
                // Add range selected to be included for navigation
                if (g.navMoveScoringItems)
                    data.ranges += ListClipperRange.fromPositions(g.navScoringNoClipRect.min.y, g.navScoringNoClipRect.max.y, 0, 0)

                // Add focused/active item
                val navRectAbs = window rectRelToAbs window.navRectRel[0]
                if (g.navId != 0 && window.navLastIds[0] == g.navId)
                    data.ranges += ListClipperRange.fromPositions(navRectAbs.min.y, navRectAbs.max.y, 0, 0)

                // Add visible range
                val offMin = if (g.navMoveScoringItems && g.navMoveClipDir == Dir.Up) -1 else 0
                val offMax = if (g.navMoveScoringItems && g.navMoveClipDir == Dir.Down) 1 else 0
                data.ranges += ListClipperRange.fromPositions(window.clipRect.min.y, window.clipRect.max.y, offMin, offMax)
            }

            // Convert position ranges to item index ranges
            // - Very important: when a starting position is after our maximum item, we set Min to (ItemsCount - 1). This allows us to handle most forms of wrapping.
            // - Due to how Selectable extra padding they tend to be "unaligned" with exact unit in the item list,
            //   which with the flooring/ceiling tend to lead to 2 items instead of one being submitted.
            for (i in data.ranges.indices)
                if (data.ranges[i].posToIndexConvert) {
                    data.ranges[i].min = clamp(alreadySubmitted + floor((data.ranges[i].min - window.dc.cursorPos.y) / itemsHeight).i + data.ranges[i].posToIndexOffsetMin, alreadySubmitted, itemsCount - 1)
                    data.ranges[i].max = clamp(alreadySubmitted + ceil((data.ranges[i].max - window.dc.cursorPos.y) / itemsHeight).i + 0 + data.ranges[i].posToIndexOffsetMax, data.ranges[i].min + 1, itemsCount)
                    data.ranges[i].posToIndexConvert = false
                }
            sortAndFuseRanges(data.ranges, data.stepNo)
        }

        // Step 0+ (if item height is given in advance) or 1+: Display the next range in line.
        if (data.stepNo < data.ranges.size) {
            displayStart = data.ranges[data.stepNo].min max alreadySubmitted
            displayEnd = data.ranges[data.stepNo].max min itemsCount
            if (displayStart > alreadySubmitted) //-V1051
                seekCursorForItem(this, displayStart)
            data.stepNo++
            return true
        }

        // After the last step: Let the clipper validate that we have reached the expected Y position (corresponding to element DisplayEnd),
        // Advance the cursor to the end of the list and then returns 'false' to end the loop.
        if (itemsCount < Int.MAX_VALUE)
            seekCursorForItem(this, itemsCount)
        itemsCount = -1

        return false
    }

    companion object {

        fun seekCursorAndSetupPrevLine(posY: Float, lineHeight: Float) {
            // Set cursor position and a few other things so that SetScrollHereY() and Columns() can work when seeking cursor.
            // FIXME: It is problematic that we have to do that here, because custom/equivalent end-user code would stumble on the same issue.
            // The clipper should probably have a final step to display the last item in a regular manner, maybe with an opt-out flag for data sets which may have costly seek?
            val window = g.currentWindow!!
            val offY = posY - window.dc.cursorPos.y
            window.dc.cursorPos.y = posY
            window.dc.cursorMaxPos.y = window.dc.cursorMaxPos.y max (posY - g.style.itemSpacing.y)
            window.dc.cursorPosPrevLine.y = window.dc.cursorPos.y - lineHeight  // Setting those fields so that SetScrollHereY() can properly function after the end of our clipper usage.
            window.dc.prevLineSize.y = lineHeight - g.style.itemSpacing.y      // If we end up needing more accurate data (to e.g. use SameLine) we may as well make the clipper have a fourth step to let user process and display the last item in their list.
            window.dc.currentColumns?.let { columns ->
                columns.lineMinY = window.dc.cursorPos.y                         // Setting this so that cell Y position are set properly
            }
            g.currentTable?.let { table ->
                if (table.isInsideRow)
                    table.endRow()
                table.rowPosY2 = window.dc.cursorPos.y
                val rowIncrease = ((offY / lineHeight) + 0.5f).i
                //table->CurrentRow += row_increase; // Can't do without fixing TableEndRow()
                table.rowBgColorCounter += rowIncrease
            }
        }

        fun seekCursorForItem(clipper: ListClipper, itemN: Int) {
            // StartPosY starts from ItemsFrozen hence the subtraction
            val data = clipper.tempData as ListClipperData
            val posY = clipper.startPosY + (itemN - data.itemsFrozen) * clipper.itemsHeight
            seekCursorAndSetupPrevLine(posY, clipper.itemsHeight)
        }

        fun sortAndFuseRanges(ranges: ArrayList<ListClipperRange>, offset: Int = 0) {
            if (ranges.size - offset <= 1)
                return

            // Helper to order ranges and fuse them together if possible (bubble sort is fine as we are only sorting 2-3 entries)
            for (sortEnd in ranges.size - offset - 1 downTo 1)
                for (i in offset until sortEnd + offset)
                    if (ranges[i].min > ranges[i + 1].min) {
                        val swap = ranges[i]
                        ranges[i] = ranges[i + 1]
                        ranges[i + 1] = swap
                    }

            // Now fuse ranges together as much as possible.
            var i = 1 + offset
            while (i < ranges.size) {
                assert(!ranges[i].posToIndexConvert && !ranges[i - 1].posToIndexConvert)
                if (ranges[i - 1].max < ranges[i].min) {
                    i++
                    continue
                }
                ranges[i - 1].min = ranges[i - 1].min min ranges[i].min
                ranges[i - 1].max = ranges[i - 1].max max ranges[i].max
                ranges.removeAt(i)
                //                i--
            }
        }
    }
}

// FIXME-TABLE: This prevents us from using ImGuiListClipper _inside_ a table cell.
// The problem we have is that without a Begin/End scheme for rows using the clipper is ambiguous.
val skipItemForListClipping: Boolean
    get() = g.currentTable?.hostSkipItems ?: g.currentWindow!!.skipItems