' Phone Mirror receiver: plays the live stream that the Roku Mirror phone app serves.
' The phone launches this channel over ECP with ?url=http://<phone>:<port>/live.m3u8

sub Main(args as dynamic)
    screen = CreateObject("roSGScreen")
    port = CreateObject("roMessagePort")
    screen.SetMessagePort(port)

    input = CreateObject("roInput")
    input.SetMessagePort(port)

    scene = screen.CreateScene("MirrorScene")
    screen.Show()

    url = UrlFrom(args)
    if url <> "" then scene.streamUrl = url

    while true
        msg = Wait(0, port)
        msgType = Type(msg)
        if msgType = "roSGScreenEvent"
            if msg.IsScreenClosed() then return
        else if msgType = "roInputEvent"
            ' The phone re-launched us while we were already open.
            url = UrlFrom(msg.GetInfo())
            if url <> "" then scene.streamUrl = url
        end if
    end while
end sub

function UrlFrom(params as dynamic) as string
    if params = invalid then return ""
    if Type(params) <> "roAssociativeArray" then return ""
    if params.DoesExist("url") and params.url <> invalid then return params.url
    return ""
end function
