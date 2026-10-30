-- AirTAK minimal vector schema v1. No external coastline or landcover downloads.
-- Multipolygon geometries are handled by tilemaker and passed through way_function.
node_keys = { "place" }
way_keys = { "highway", "railway", "waterway", "natural", "landuse", "leisure", "building" }

function init_function() end
function exit_function() end

local function name_attribute()
    local name = Find("name")
    if name ~= "" then Attribute("name", name) end
end

function node_function()
    local place = Find("place")
    if place ~= "" then
        Layer("place", false)
        Attribute("class", place)
        name_attribute()
        if place == "city" or place == "town" then MinZoom(6)
        elseif place == "village" then MinZoom(9)
        else MinZoom(11) end
    end
end

function way_function()
    local highway = Find("highway")
    local railway = Find("railway")
    local natural = Find("natural")
    local landuse = Find("landuse")
    local waterway = Find("waterway")
    local leisure = Find("leisure")
    local building = Find("building")

    if highway ~= "" and highway ~= "proposed" then
        Layer("transportation", false)
        Attribute("class", highway)
        name_attribute()
        if highway == "motorway" or highway == "trunk" or highway == "primary" then MinZoom(6)
        elseif highway == "secondary" or highway == "tertiary" then MinZoom(8)
        else MinZoom(10) end
    elseif railway == "rail" then
        Layer("transportation", false)
        Attribute("class", "rail")
        MinZoom(9)
    end

    if waterway ~= "" and not IsClosed() then
        Layer("waterway", false)
        Attribute("class", waterway)
        name_attribute()
        if waterway ~= "river" then MinZoom(10) end
    end

    if IsClosed() then
        if natural == "water" or landuse == "reservoir" or landuse == "basin" then
            Layer("water", true)
            Attribute("class", "water")
            name_attribute()
        elseif natural == "wood" or landuse ~= "" or leisure == "park" or natural == "scrub" then
            Layer("landuse", true)
            local class = landuse
            if class == "" then class = natural end
            if class == "" then class = leisure end
            Attribute("class", class)
        end
        if building ~= "" and building ~= "no" then
            Layer("building", true)
            Attribute("class", building)
            MinZoom(12)
        end
    end
end
