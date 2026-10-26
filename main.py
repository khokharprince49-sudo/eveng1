"""G1 Text Bridge: Python/Kivy UI for sending simple text to Even Realities G1."""
from __future__ import annotations

import threading
from kivy.app import App
from kivy.clock import Clock
from kivy.metrics import dp
from kivy.uix.boxlayout import BoxLayout
from kivy.uix.button import Button
from kivy.uix.label import Label
from kivy.uix.textinput import TextInput
from kivy.utils import platform

from g1_protocol import build_text_packets


class _BridgeListener:
    """Created lazily on Android so desktop preview doesn't require PyJNIus."""
    def __new__(cls, app):
        from jnius import PythonJavaClass, java_method

        class Listener(PythonJavaClass):
            __javainterfaces__ = ['com/example/g1text/G1BluetoothBridge$Listener']
            __javacontext__ = 'app'

            def __init__(self, app_ref):
                super().__init__()
                self.app_ref = app_ref

            @java_method('(Ljava/lang/String;)V')
            def onStatus(self, message):
                Clock.schedule_once(lambda _dt: self.app_ref.set_status(str(message)), 0)

            @java_method('(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;)V')
            def onDeviceFound(self, side, name, address):
                Clock.schedule_once(
                    lambda _dt: self.app_ref.set_device(str(side), str(name), str(address)), 0
                )

            @java_method('()V')
            def onConnected(self):
                Clock.schedule_once(lambda _dt: self.app_ref.set_connected(True), 0)

        return Listener(app)


class G1TextApp(App):
    title = 'G1 Text Bridge'

    def build(self):
        self.bridge = None
        self.listener = None
        self.devices = {}
        self.connected = False
        self._send_lock = threading.Lock()

        root = BoxLayout(orientation='vertical', padding=dp(18), spacing=dp(12))
        root.add_widget(Label(text='G1 TEXT BRIDGE', font_size='23sp', bold=True,
                              size_hint_y=None, height=dp(42), color=(0.15, 0.9, 0.62, 1)))
        root.add_widget(Label(text='Type a short message and send it to your G1 display.',
                              size_hint_y=None, height=dp(38), halign='left', valign='middle'))

        self.status_label = Label(text='Not connected. Put on your glasses and enable Bluetooth.',
                                  size_hint_y=None, height=dp(64), halign='left', valign='middle')
        self.status_label.bind(size=lambda inst, _val: setattr(inst, 'text_size', (inst.width, None)))
        root.add_widget(self.status_label)

        self.text_input = TextInput(
            text='Hello from G1 Text Bridge!', hint_text='Enter text to show on your glasses…',
            multiline=True, size_hint_y=1, font_size='18sp', padding=dp(12),
        )
        root.add_widget(self.text_input)

        self.counter_label = Label(text='Up to around 5 lines per screen; longer text is paged.',
                                   size_hint_y=None, height=dp(32), color=(0.7, 0.75, 0.78, 1))
        root.add_widget(self.counter_label)

        actions = BoxLayout(size_hint_y=None, height=dp(52), spacing=dp(8))
        self.connect_button = Button(text='Scan & Connect')
        self.connect_button.bind(on_release=lambda *_: self.start_connection())
        actions.add_widget(self.connect_button)
        self.send_button = Button(text='Send text', disabled=True)
        self.send_button.bind(on_release=lambda *_: self.send_text())
        actions.add_widget(self.send_button)
        root.add_widget(actions)

        clear_button = Button(text='Clear glasses display', size_hint_y=None, height=dp(48),
                              disabled=True)
        clear_button.bind(on_release=lambda *_: self.clear_display())
        self.clear_button = clear_button
        root.add_widget(clear_button)
        root.add_widget(Label(text='Unofficial community project • Bluetooth only',
                              size_hint_y=None, height=dp(26), color=(0.55, 0.6, 0.63, 1),
                              font_size='12sp'))
        if platform != 'android':
            self.status_label.text = 'Preview only. Install on Android to use Bluetooth.'
        return root

    def on_start(self):
        if platform == 'android':
            Clock.schedule_once(lambda _dt: self.start_connection(), 0.7)

    def set_status(self, message):
        self.status_label.text = message

    def set_device(self, side, name, address):
        self.devices[side] = {'name': name, 'address': address}
        self.status_label.text = f"Found {side} glass: {name}"

    def set_connected(self, connected):
        self.connected = connected
        self.send_button.disabled = not connected
        self.clear_button.disabled = not connected
        self.connect_button.text = 'Connected' if connected else 'Scan & Connect'
        if connected:
            found = ', '.join(v['name'] for v in self.devices.values())
            self.status_label.text = 'Connected to both glasses.' + (f' ({found})' if found else '')

    def start_connection(self):
        if platform != 'android':
            self.set_status('Bluetooth connection is available in the Android build only.')
            return
        try:
            from android.permissions import request_permissions
            from jnius import autoclass
            sdk_int = int(autoclass('android.os.Build$VERSION').SDK_INT)
            if sdk_int >= 31:
                required = [
                    'android.permission.BLUETOOTH_SCAN',
                    'android.permission.BLUETOOTH_CONNECT',
                ]
            else:
                required = ['android.permission.ACCESS_FINE_LOCATION']
            request_permissions(required, self._permissions_result)
        except Exception as exc:
            self.set_status(f'Could not request Bluetooth permissions: {exc}')

    def _permissions_result(self, permissions, grants):
        # Kivy's Android permission callback is invoked on the UI thread.
        if not all(bool(x) for x in grants):
            self.set_status('Bluetooth permissions are required. Allow them and tap Scan & Connect.')
            return
        try:
            if self.bridge is None:
                from jnius import autoclass
                self.listener = _BridgeListener(self)
                Bridge = autoclass('com.example.g1text.G1BluetoothBridge')
                PythonActivity = autoclass('org.kivy.android.PythonActivity')
                self.bridge = Bridge(PythonActivity.mActivity, self.listener)
            self.set_status('Scanning for the left and right G1 glasses…')
            self.bridge.startScan()
        except Exception as exc:
            self.set_status(f'Bluetooth start failed: {exc}')

    def send_text(self):
        if not self.connected or self.bridge is None:
            self.set_status('Connect both glasses first.')
            return
        text = self.text_input.text.strip()
        if not text:
            self.set_status('Type some text first.')
            return
        try:
            packets = build_text_packets(text)
            with self._send_lock:
                for index, packet in enumerate(packets):
                    # Java bridge queues each packet to both lenses in order.
                    self.bridge.sendPacket(bytes(packet).hex())
            self.set_status(f'Sent {len(packets)} page packet(s). If the display does not update, reconnect and retry.')
        except Exception as exc:
            self.set_status(f'Could not send text: {exc}')

    def clear_display(self):
        try:
            if self.bridge is None or not self.connected:
                self.set_status('Connect both glasses first.')
                return
            self.bridge.sendPacket('18')
            self.set_status('Clear-display command sent.')
        except Exception as exc:
            self.set_status(f'Could not clear display: {exc}')

    def on_stop(self):
        try:
            if self.bridge is not None:
                self.bridge.close()
        except Exception:
            pass


if __name__ == '__main__':
    G1TextApp().run()
