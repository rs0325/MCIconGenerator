"""World QAの静的参照画像だけを無agent撮影の事前条件にする。"""

DYNAMIC_IDS = {'clock', 'compass', 'recovery_compass', 'enchanted_book', 'sea_lantern'}


def has_static_references(ids, entries):
    static_ids = [item for item in ids if item not in DYNAMIC_IDS]
    return all(
        'minecraft:' + item in entries and entries['minecraft:' + item].get('rawPassed', False)
        for item in static_ids
    )
