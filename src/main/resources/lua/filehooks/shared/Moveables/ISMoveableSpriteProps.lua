-- Run by LuaFileHooks right after the game's ISMoveableSpriteProps.lua loads, so
-- this wrapper sits beneath every mod override of placeMoveableInternal.
--
-- 42.21 changed placeMoveableInternal(_square, _item, _spriteName) to
-- placeMoveableInternal(_character, _square, _item, _spriteName). Mods written
-- for 42.20 break against the new signature in two ways:
--
--   * An old-form call shifts every argument one slot left, so vanilla runs
--     _square:getObjects() on an InventoryItem and getSprite(nil).
--   * An old-form pass-through wrapper, hit by a new-form caller, forwards
--     (character, square, item) and drops the sprite name.
--
-- Old-form calls get their arguments shifted back with no character, which the
-- vanilla body tolerates. A dropped sprite name is recovered from the item.

if not ISMoveableSpriteProps or not ISMoveableSpriteProps.placeMoveableInternal then
    return
end

local placeMoveableInternal = ISMoveableSpriteProps.placeMoveableInternal
local warned = {}

local function warnOnce(reason)
    if not warned[reason] then
        warned[reason] = true
        print("[Storm] placeMoveableInternal: " .. reason .. " (pre-42.21 mod call)")
    end
end

function ISMoveableSpriteProps:placeMoveableInternal(_character, _square, _item, _spriteName)
    if instanceof(_character, "IsoGridSquare") then
        warnOnce("shifted 3-argument call")
        _character, _square, _item, _spriteName = nil, _character, _square, _item
    end
    if _spriteName == nil then
        warnOnce("recovered dropped sprite name")
        if instanceof(_item, "Moveable") then
            _spriteName = _item:getWorldSprite()
        end
        if _spriteName == nil or _spriteName == "" then
            _spriteName = self.spriteName
        end
    end
    return placeMoveableInternal(self, _character, _square, _item, _spriteName)
end
