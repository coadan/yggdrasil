module Acme.Panels.PanelService where

import Data.Text
import qualified Data.Aeson as Aeson

data Panel = Panel Text

loadPanel :: Text -> Panel
loadPanel panelId = Panel panelId
