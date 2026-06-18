project = 'Panels Manual'
author = 'Panel Team'

extensions = [
    'sphinx.ext.autodoc',
    'myst_parser',
]

templates_path = ['_templates']
exclude_patterns = ['_build']

root_doc = 'index'
html_theme = 'furo'
html_static_path = ['_static']
