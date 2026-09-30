-- Asks the server whether Storm's bootstrap agent is attached. The mod's server Lua loads without
-- the agent too, so a server that only subscribed to the workshop item still answers the ping, and
-- it answers that Storm is off.
require("ISUI/ISModalRichText")

local MODULE = "stormBootstrap"
local PING = "ping"
local PONG = "pong"

local WORKSHOP_PATH = "./steamapps/workshop/content/108600/3670772371/mods/storm/bootstrap/"
local SERVER_FLAG = "-Dstorm.server=true"
local AGENT_FLAGS = {
    {
        labelKey = "UI_Storm_ServerNoStorm_Linux",
        flag = "-javaagent:" .. WORKSHOP_PATH .. "storm-bootstrap.jar",
    },
    {
        labelKey = "UI_Storm_ServerNoStorm_Windows",
        flag = "-agentpath:" .. WORKSHOP_PATH .. "agentlib.dll=storm-bootstrap.jar",
    },
}
local GUIDE_URL = "github.com/guspuffygit/project-zomboid-storm/blob/main/docs/installation.md"

local MIN_WIDTH = 560
local TEXT_MARGIN = 60
local START_HEIGHT = 120

local modal

local function heading(text)
    return " <LINE> <RGB:1,0.55,0.25> " .. text .. " <RGB:1,1,1> <LINE> "
end

local function misconfiguredText()
    local text = " <SIZE:large> "
        .. getText("UI_Storm_ServerNoStorm_Title")
        .. " <LINE> <SIZE:small> <LINE> "
        .. getText("UI_Storm_ServerNoStorm_Intro")
        .. " <LINE> <LINE> "
        .. getText("UI_Storm_ServerNoStorm_Fix")
        .. " <LINE> "
    for _, row in ipairs(AGENT_FLAGS) do
        text = text
            .. heading(getText(row.labelKey))
            .. row.flag
            .. " <LINE> "
            .. SERVER_FLAG
            .. " <LINE> "
    end
    return text
        .. " <LINE> "
        .. getText("UI_Storm_ServerNoStorm_Hosted")
        .. " <LINE> <LINE> "
        .. getText("UI_Storm_ServerNoStorm_Guide")
        .. " <LINE> "
        .. GUIDE_URL
        .. " <LINE> "
end

-- A flag has no spaces, so the rich text cannot wrap it and the panel has to fit it on one line.
local function modalWidth()
    local width = MIN_WIDTH
    for _, row in ipairs(AGENT_FLAGS) do
        local flagWidth = getTextManager():MeasureStringX(UIFont.NewSmall, row.flag)
        width = math.max(width, flagWidth + TEXT_MARGIN)
    end
    return math.min(width, getCore():getScreenWidth() - 40)
end

local function hideMisconfiguredModal()
    if not modal then
        return
    end
    modal:removeFromUIManager()
    modal = nil
end

local function showMisconfiguredModal()
    if modal then
        return
    end

    local core = getCore()
    local width = modalWidth()
    local x = (core:getScreenWidth() - width) / 2
    local y = (core:getScreenHeight() - START_HEIGHT) / 2

    modal = ISModalRichText:new(
        x,
        y,
        width,
        START_HEIGHT,
        misconfiguredText(),
        false,
        nil,
        function()
            modal = nil
        end,
        0
    )
    modal:initialise()
    modal:addToUIManager()
    modal:bringToTop()

    if JoypadState.players[1] then
        modal.prevFocus = getJoypadFocus(0)
        setJoypadFocus(0, modal)
    end
end

-- OnGameStart fires before the client sends PlayerConnect, and the server drops client commands
-- from a connection that has no player yet. The first tick comes after PlayerConnect.
local function onTick()
    Events.OnTick.Remove(onTick)
    if isClient() then
        sendClientCommand(MODULE, PING, {})
    end
end

local function onServerCommand(module, command, args)
    if module ~= MODULE or command ~= PONG then
        return
    end
    if args and args.enabled == false then
        print("[Storm] This server runs the Storm mod without the Storm bootstrap agent")
        showMisconfiguredModal()
    end
end

Events.OnTick.Add(onTick)
Events.OnServerCommand.Add(onServerCommand)
Events.OnMainMenuEnter.Add(hideMisconfiguredModal)
Events.OnDisconnect.Add(hideMisconfiguredModal)
