sub init()
    m.video = m.top.FindNode("video")
    m.overlay = m.top.FindNode("overlay")
    m.status = m.top.FindNode("status")
    m.retry = m.top.FindNode("retry")
    m.attempts = 0
    m.video.ObserveField("state", "onVideoState")
    m.retry.ObserveField("fire", "play")
    m.top.SetFocus(true)
end sub

sub onStreamUrl()
    m.attempts = 0
    play()
end sub

sub play()
    if m.top.streamUrl = invalid or m.top.streamUrl = "" then return
    content = CreateObject("roSGNode", "ContentNode")
    content.url = m.top.streamUrl
    content.streamFormat = "hls"
    content.live = true
    content.title = "Phone"
    m.video.control = "stop"
    m.video.content = content
    m.video.control = "play"
    m.overlay.visible = true
    m.status.text = "Connecting to your phone..."
end sub

sub onVideoState()
    state = m.video.state
    if state = "playing"
        m.attempts = 0
        m.overlay.visible = false
    else if state = "buffering"
        ' Keep the last picture on screen; nothing to do.
    else if state = "error" or state = "finished"
        m.overlay.visible = true
        m.attempts = m.attempts + 1
        if m.attempts > 15
            m.status.text = "Lost the phone's stream. Tap Start on your phone to reconnect."
        else
            m.status.text = "Waiting for your phone... (" + m.video.errorMsg + ")"
            m.retry.control = "start"
        end if
    end if
end sub

function onKeyEvent(key as string, press as boolean) as boolean
    ' Let Back exit the channel; swallow other keys so they don't open video UI.
    if not press then return false
    if key = "back" then return false
    return true
end function
